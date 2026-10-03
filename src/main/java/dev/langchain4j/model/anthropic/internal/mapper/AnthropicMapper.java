package dev.langchain4j.model.anthropic.internal.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.Exceptions;
import dev.langchain4j.internal.Utils;
import dev.langchain4j.internal.ValidationUtils;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContentBlockType;
import dev.langchain4j.model.anthropic.internal.api.AnthropicImageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRedactedThinkingContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicThinkingContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRole;
import dev.langchain4j.model.anthropic.internal.api.AnthropicTextContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicTool;
import dev.langchain4j.model.anthropic.internal.api.AnthropicToolResultContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicToolSchema;
import dev.langchain4j.model.anthropic.internal.api.AnthropicToolUseContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Enhanced AnthropicMapper supporting thinking and reasoning models (DeepSeek R1, DeepSeek-V4, Claude 3.7).
 */
public class AnthropicMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Holds the thinking/reasoning text captured from the most recent toAiMessage() call
     * on the same thread. LangChainAgentService reads this after chatModel.generate() returns
     * to surface reasoning to the UI via onThinking().
     */
    public static final ThreadLocal<String> LAST_THINKING =
            com.github.axiomate.agentic.ide.agent.ReasoningContext.LAST_REASONING;

    /**
     * Reasoning blocks of assistant turns that called tools, keyed by the AiMessage instance (identity), so they
     * can be sent back with that turn. Cleared by the agent when a task ends.
     */
    private static final Map<AiMessage, List<AnthropicMessageContent>> THINKING_REPLAY =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public static void clearThinkingReplay() {
        THINKING_REPLAY.clear();
    }

    static int thinkingReplaySize() {
        return THINKING_REPLAY.size();
    }

    public AnthropicMapper() {
    }

    public static List<AnthropicMessage> toAnthropicMessages(List<ChatMessage> messages) {
        List<AnthropicMessage> rawMessages = new ArrayList<>();
        List<AnthropicMessageContent> toolResultContents = new ArrayList<>();

        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {
                AnthropicToolResultContent trc = toAnthropicToolResultContent(toolExecutionResultMessage);
                if (trc != null) {
                    toolResultContents.add(trc);
                }
            } else {
                if (!toolResultContents.isEmpty()) {
                    rawMessages.add(new AnthropicMessage(AnthropicRole.USER, new ArrayList<>(toolResultContents)));
                    toolResultContents.clear();
                }

                if (message instanceof UserMessage userMessage) {
                    List<AnthropicMessageContent> contents = toAnthropicMessageContents(userMessage);
                    if (!contents.isEmpty()) {
                        rawMessages.add(new AnthropicMessage(AnthropicRole.USER, contents));
                    }
                } else if (message instanceof AiMessage aiMessage) {
                    List<AnthropicMessageContent> contents = toAnthropicMessageContents(aiMessage);
                    if (!contents.isEmpty()) {
                        rawMessages.add(new AnthropicMessage(AnthropicRole.ASSISTANT, contents));
                    }
                }
            }
        }

        if (!toolResultContents.isEmpty()) {
            rawMessages.add(new AnthropicMessage(AnthropicRole.USER, new ArrayList<>(toolResultContents)));
            toolResultContents.clear();
        }

        if (rawMessages.isEmpty()) {
            return List.of(new AnthropicMessage(AnthropicRole.USER, List.of(new AnthropicTextContent("Hello"))));
        }

        // Merge consecutive messages with the same role (Anthropic API requires strict alternation between USER and ASSISTANT)
        List<AnthropicMessage> mergedMessages = new ArrayList<>();
        for (AnthropicMessage msg : rawMessages) {
            if (msg.content == null || msg.content.isEmpty()) {
                continue;
            }
            if (!mergedMessages.isEmpty() && mergedMessages.get(mergedMessages.size() - 1).role == msg.role) {
                List<AnthropicMessageContent> combined = new ArrayList<>(mergedMessages.get(mergedMessages.size() - 1).content);
                combined.addAll(msg.content);
                mergedMessages.set(mergedMessages.size() - 1, new AnthropicMessage(msg.role, combined));
            } else {
                mergedMessages.add(msg);
            }
        }

        // Ensure the conversation starts with a USER message
        if (!mergedMessages.isEmpty() && mergedMessages.get(0).role != AnthropicRole.USER) {
            mergedMessages.add(0, new AnthropicMessage(AnthropicRole.USER, List.of(new AnthropicTextContent("Proceed with the task."))));
        }

        // Final safety guarantee: ensure every message has at least one valid, non-empty content block
        List<AnthropicMessage> validatedMessages = new ArrayList<>();
        for (AnthropicMessage msg : mergedMessages) {
            List<AnthropicMessageContent> validBlocks = new ArrayList<>();
            for (AnthropicMessageContent block : msg.content) {
                if (block instanceof AnthropicTextContent textBlock) {
                    if (textBlock.text != null && !textBlock.text.trim().isEmpty()) {
                        validBlocks.add(textBlock);
                    }
                } else if (block instanceof AnthropicToolResultContent toolResultBlock) {
                    String text = toolResultBlock.content;
                    if (text == null || text.trim().isEmpty()) {
                        text = "(success)";
                    }
                    validBlocks.add(new AnthropicToolResultContent(toolResultBlock.toolUseId, text, toolResultBlock.isError));
                } else if (block != null) {
                    validBlocks.add(block);
                }
            }
            if (validBlocks.isEmpty()) {
                validBlocks.add(new AnthropicTextContent(msg.role == AnthropicRole.USER ? "(user input)" : "(response)"));
            }
            validatedMessages.add(new AnthropicMessage(msg.role, validBlocks));
        }

        return validatedMessages.isEmpty()
                ? List.of(new AnthropicMessage(AnthropicRole.USER, List.of(new AnthropicTextContent("Hello"))))
                : validatedMessages;
    }

    private static AnthropicToolResultContent toAnthropicToolResultContent(ToolExecutionResultMessage resultMessage) {
        String text = resultMessage.text();
        if (text == null || text.trim().isEmpty()) {
            text = "(success)";
        }
        String id = resultMessage.id();
        if (id == null || id.trim().isEmpty()) {
            id = "call_default";
        }
        return new AnthropicToolResultContent(id, text, null);
    }

    private static List<AnthropicMessageContent> toAnthropicMessageContents(UserMessage userMessage) {
        List<AnthropicMessageContent> contents = new ArrayList<>();
        if (userMessage.contents() != null) {
            for (Content content : userMessage.contents()) {
                if (content instanceof TextContent textContent) {
                    if (textContent.text() != null && !textContent.text().trim().isEmpty()) {
                        contents.add(new AnthropicTextContent(textContent.text()));
                    }
                } else if (content != null) {
                    contents.add(toAnthropicMessageContent(content));
                }
            }
        }
        if (contents.isEmpty() && Utils.isNotNullOrBlank(userMessage.singleText())) {
            contents.add(new AnthropicTextContent(userMessage.singleText()));
        }
        if (contents.isEmpty()) {
            contents.add(new AnthropicTextContent("(user prompt)"));
        }
        return contents;
    }

    private static AnthropicMessageContent toAnthropicMessageContent(Content content) {
        if (content instanceof TextContent textContent) {
            String text = textContent.text();
            return new AnthropicTextContent((text != null && !text.trim().isEmpty()) ? text : "(empty)");
        } else if (content instanceof ImageContent imageContent) {
            Image image = imageContent.image();
            if (image.url() != null) {
                throw Exceptions.illegalArgument("Anthropic does not support images as URLs, only as Base64-encoded strings");
            }
            return new AnthropicImageContent(
                    ValidationUtils.ensureNotBlank(image.mimeType(), "mimeType"),
                    ValidationUtils.ensureNotBlank(image.base64Data(), "base64Data")
            );
        } else {
            throw Exceptions.illegalArgument("Unknown content type: " + content);
        }
    }

    private static List<AnthropicMessageContent> toAnthropicMessageContents(AiMessage aiMessage) {
        List<AnthropicMessageContent> contents = new ArrayList<>();
        // Reasoning comes first in the turn, exactly as the model produced it
        List<AnthropicMessageContent> thinking = THINKING_REPLAY.get(aiMessage);
        if (thinking != null) {
            contents.addAll(thinking);
        }
        if (Utils.isNotNullOrBlank(aiMessage.text())) {
            contents.add(new AnthropicTextContent(aiMessage.text()));
        }
        if (aiMessage.hasToolExecutionRequests()) {
            contents.addAll(aiMessage.toolExecutionRequests().stream()
                    .map(AnthropicMapper::toAnthropicToolUseContent)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        }
        if (contents.isEmpty()) {
            contents.add(new AnthropicTextContent("(response)"));
        }
        return contents;
    }

    private static AnthropicToolUseContent toAnthropicToolUseContent(ToolExecutionRequest req) {
        try {
            Map<String, Object> inputMap = null;
            if (req.arguments() != null && !req.arguments().trim().isEmpty()) {
                try {
                    inputMap = OBJECT_MAPPER.readValue(req.arguments(), java.util.Map.class);
                } catch (Exception ignored) {
                    Map<String, Object> fallback = new HashMap<>();
                    fallback.put("input", req.arguments());
                    inputMap = fallback;
                }
            }
            if (inputMap == null) {
                inputMap = Collections.emptyMap();
            }
            String id = (req.id() != null && !req.id().trim().isEmpty())
                    ? req.id()
                    : ("call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            String name = (req.name() != null && !req.name().trim().isEmpty())
                    ? req.name()
                    : "tool";
            return AnthropicToolUseContent.builder()
                    .id(id)
                    .name(name)
                    .input(inputMap)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toAnthropicSystemPrompt(List<ChatMessage> messages) {
        String systemPrompt = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> ((SystemMessage) m).text())
                .collect(Collectors.joining("\n\n"));
        return Utils.isNullOrBlank(systemPrompt) ? null : systemPrompt;
    }

    public static AiMessage toAiMessage(List<AnthropicContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return AiMessage.from("");
        }

        String text = contents.stream()
                .filter(c -> c.type == AnthropicContentBlockType.TEXT)
                .map(c -> c.text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        String thinking = contents.stream()
                .filter(c -> c.type == AnthropicContentBlockType.THINKING)
                .map(c -> c.thinking)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        List<ToolExecutionRequest> toolExecutionRequests = contents.stream()
                .filter(c -> c.type == AnthropicContentBlockType.TOOL_USE)
                .map(c -> {
                    try {
                        return ToolExecutionRequest.builder()
                                .id(c.id)
                                .name(c.name)
                                .arguments(OBJECT_MAPPER.writeValueAsString(c.input))
                                .build();
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        // Store thinking separately via thread-local so the agent service can
        // surface it through onThinking() without embedding it in the chat text.
        LAST_THINKING.set(Utils.isNotNullOrBlank(thinking) ? thinking : null);

        // The AiMessage text is ONLY the final answer (not the thinking)
        String combinedText = Utils.isNotNullOrBlank(text) ? text : "";

        AiMessage result;
        if (Utils.isNotNullOrBlank(combinedText) && !Utils.isNullOrEmpty(toolExecutionRequests)) {
            result = AiMessage.from(combinedText, toolExecutionRequests);
        } else if (!Utils.isNullOrEmpty(toolExecutionRequests)) {
            result = AiMessage.from(toolExecutionRequests);
        } else {
            result = AiMessage.from(combinedText);
        }

        // Keep the reasoning of tool-calling turns so it is sent back with them on the next request
        if (!Utils.isNullOrEmpty(toolExecutionRequests)) {
            List<AnthropicMessageContent> blocks = new ArrayList<>();
            for (AnthropicContent c : contents) {
                if (c.type == AnthropicContentBlockType.THINKING && c.thinking != null) {
                    blocks.add(new AnthropicThinkingContent(c.thinking, c.signature));
                } else if (c.type == AnthropicContentBlockType.REDACTED_THINKING && c.data != null) {
                    blocks.add(new AnthropicRedactedThinkingContent(c.data));
                }
            }
            if (!blocks.isEmpty()) {
                THINKING_REPLAY.put(result, blocks);
            }
        }
        return result;
    }

    public static TokenUsage toTokenUsage(AnthropicUsage usage) {
        if (usage == null) {
            return null;
        }
        return new TokenUsage(usage.inputTokens, usage.outputTokens);
    }

    public static FinishReason toFinishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn" -> FinishReason.STOP;
            case "max_tokens" -> FinishReason.LENGTH;
            case "stop_sequence" -> FinishReason.OTHER;
            case "tool_use" -> FinishReason.TOOL_EXECUTION;
            default -> null;
        };
    }

    public static List<AnthropicTool> toAnthropicTools(List<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null) {
            return null;
        }
        return toolSpecifications.stream()
                .map(AnthropicMapper::toAnthropicTool)
                .collect(Collectors.toList());
    }

    public static AnthropicTool toAnthropicTool(ToolSpecification toolSpecification) {
        ToolParameters parameters = toolSpecification.parameters();
        return AnthropicTool.builder()
                .name(toolSpecification.name())
                .description(toolSpecification.description())
                .inputSchema(AnthropicToolSchema.builder()
                        .properties(parameters != null ? parameters.properties() : Collections.emptyMap())
                        .required(parameters != null ? parameters.required() : Collections.emptyList())
                        .build())
                .build();
    }
}

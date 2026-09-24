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
import java.util.List;
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
    public static final ThreadLocal<String> LAST_THINKING = new ThreadLocal<>();

    public AnthropicMapper() {
    }

    public static List<AnthropicMessage> toAnthropicMessages(List<ChatMessage> messages) {
        List<AnthropicMessage> anthropicMessages = new ArrayList<>();
        List<AnthropicMessageContent> toolResultContents = new ArrayList<>();

        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage toolExecutionResultMessage) {
                toolResultContents.add(toAnthropicToolResultContent(toolExecutionResultMessage));
            } else {
                if (!toolResultContents.isEmpty()) {
                    anthropicMessages.add(new AnthropicMessage(AnthropicRole.USER, toolResultContents));
                    toolResultContents = new ArrayList<>();
                }

                if (message instanceof UserMessage userMessage) {
                    anthropicMessages.add(new AnthropicMessage(AnthropicRole.USER, toAnthropicMessageContents(userMessage)));
                } else if (message instanceof AiMessage aiMessage) {
                    anthropicMessages.add(new AnthropicMessage(AnthropicRole.ASSISTANT, toAnthropicMessageContents(aiMessage)));
                }
            }
        }

        if (!toolResultContents.isEmpty()) {
            anthropicMessages.add(new AnthropicMessage(AnthropicRole.USER, toolResultContents));
        }

        return anthropicMessages;
    }

    private static AnthropicToolResultContent toAnthropicToolResultContent(ToolExecutionResultMessage resultMessage) {
        return new AnthropicToolResultContent(resultMessage.id(), resultMessage.text(), null);
    }

    private static List<AnthropicMessageContent> toAnthropicMessageContents(UserMessage userMessage) {
        return userMessage.contents().stream()
                .map(AnthropicMapper::toAnthropicMessageContent)
                .collect(Collectors.toList());
    }

    private static AnthropicMessageContent toAnthropicMessageContent(Content content) {
        if (content instanceof TextContent textContent) {
            return new AnthropicTextContent(textContent.text());
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
        if (Utils.isNotNullOrBlank(aiMessage.text())) {
            contents.add(new AnthropicTextContent(aiMessage.text()));
        }
        if (aiMessage.hasToolExecutionRequests()) {
            contents.addAll(aiMessage.toolExecutionRequests().stream()
                    .map(AnthropicMapper::toAnthropicToolUseContent)
                    .collect(Collectors.toList()));
        }
        return contents;
    }

    private static AnthropicToolUseContent toAnthropicToolUseContent(ToolExecutionRequest req) {
        try {
            return AnthropicToolUseContent.builder()
                    .id(req.id())
                    .name(req.name())
                    .input(OBJECT_MAPPER.readValue(req.arguments(), java.util.Map.class))
                    .build();
        } catch (JsonProcessingException e) {
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

        if (Utils.isNotNullOrBlank(combinedText) && !Utils.isNullOrEmpty(toolExecutionRequests)) {
            return AiMessage.from(combinedText, toolExecutionRequests);
        } else if (!Utils.isNullOrEmpty(toolExecutionRequests)) {
            return AiMessage.from(toolExecutionRequests);
        } else {
            return AiMessage.from(combinedText);
        }
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

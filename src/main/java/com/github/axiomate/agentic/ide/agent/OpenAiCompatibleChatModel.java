package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat model for OpenAI-compatible Chat Completions APIs (OpenAI, DeepSeek, Ollama, LM Studio, vLLM,
 * OpenRouter...) with first-class support for reasoning models.
 * <p>
 * Unlike LangChain4j's OpenAiChatModel, which drops it, this client keeps the reasoning a model returns
 * ({@code reasoning_content}, {@code reasoning}, or inline {@code <think>} tags), surfaces it through
 * {@link ReasoningContext}, and sends it back with assistant turns that called tools. DeepSeek's thinking mode
 * requires that within a tool-using turn; without it the model loses its plan and repeats the same tool calls.
 */
public class OpenAiCompatibleChatModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern THINK_TAG = Pattern.compile("(?s)^\\s*<think>(.*?)</think>\\s*");

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final Duration timeout;
    private final HttpClient http;
    /** Some servers reject reasoning_content in requests; after the first such error it is no longer sent. */
    private volatile boolean sendReasoning = true;

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String modelName, Double temperature,
                                     Integer maxTokens, Duration timeout) {
        String url = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, List.of());
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return generate(messages, List.of(toolSpecification));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        try {
            try {
                return send(buildRequest(messages, toolSpecifications, sendReasoning));
            } catch (HttpError e) {
                if (sendReasoning && e.status == 400 && e.body.toLowerCase().contains("reasoning")) {
                    log.info("Server rejected reasoning_content in the request; retrying without it");
                    sendReasoning = false;
                    return send(buildRequest(messages, toolSpecifications, false));
                }
                throw e;
            }
        } catch (HttpError e) {
            throw new RuntimeException("HTTP " + e.status + " from " + baseUrl + ": " + e.body, e);
        } catch (IOException e) {
            throw new RuntimeException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request to " + baseUrl + " was interrupted", e);
        }
    }

    // ------------------------------------------------------------------
    // Request
    // ------------------------------------------------------------------

    ObjectNode buildRequest(List<ChatMessage> messages, List<ToolSpecification> tools, boolean includeReasoning) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", modelName);
        if (temperature != null) root.put("temperature", temperature);
        if (maxTokens != null && maxTokens > 0) root.put("max_tokens", maxTokens);
        ArrayNode msgs = root.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode node = toJson(m, includeReasoning);
            if (node != null) msgs.add(node);
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsNode = root.putArray("tools");
            for (ToolSpecification t : tools) {
                ObjectNode fn = MAPPER.createObjectNode();
                fn.put("name", t.name());
                if (t.description() != null) fn.put("description", t.description());
                fn.set("parameters", parametersSchema(t.parameters()));
                ObjectNode wrapper = toolsNode.addObject();
                wrapper.put("type", "function");
                wrapper.set("function", fn);
            }
        }
        return root;
    }

    private static ObjectNode parametersSchema(ToolParameters p) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        if (p != null && p.properties() != null) {
            for (Map.Entry<String, Map<String, Object>> e : p.properties().entrySet()) {
                props.set(e.getKey(), MAPPER.valueToTree(e.getValue()));
            }
        }
        if (p != null && p.required() != null && !p.required().isEmpty()) {
            ArrayNode req = schema.putArray("required");
            p.required().forEach(req::add);
        }
        return schema;
    }

    private static ObjectNode toJson(ChatMessage m, boolean includeReasoning) {
        ObjectNode node = MAPPER.createObjectNode();
        if (m instanceof SystemMessage sm) {
            node.put("role", "system");
            node.put("content", sm.text());
        } else if (m instanceof UserMessage um) {
            node.put("role", "user");
            if (hasImages(um)) {
                node.set("content", multimodalContent(um));
            } else {
                node.put("content", userText(um));
            }
        } else if (m instanceof AiMessage am) {
            node.put("role", "assistant");
            if (am.text() != null && !am.text().isBlank()) {
                node.put("content", am.text());
            } else {
                node.putNull("content");
            }
            if (am.hasToolExecutionRequests()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolExecutionRequest r : am.toolExecutionRequests()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", r.id());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", r.name());
                    fn.put("arguments", r.arguments() == null || r.arguments().isBlank() ? "{}" : r.arguments());
                }
                String reasoning = ReasoningContext.replayFor(am);
                if (includeReasoning && reasoning != null) {
                    node.put("reasoning_content", reasoning);
                }
            }
        } else if (m instanceof ToolExecutionResultMessage tr) {
            node.put("role", "tool");
            node.put("tool_call_id", tr.id());
            node.put("content", tr.text() == null || tr.text().isBlank() ? "(no output)" : tr.text());
        } else {
            return null;
        }
        return node;
    }

    private static boolean hasImages(UserMessage um) {
        return um.contents().stream().anyMatch(c -> c instanceof ImageContent);
    }

    /** OpenAI vision format: text parts plus image_url parts carrying data URLs (also used by Ollama, vLLM, LM Studio). */
    private static ArrayNode multimodalContent(UserMessage um) {
        ArrayNode parts = MAPPER.createArrayNode();
        for (Content c : um.contents()) {
            if (c instanceof TextContent tc) {
                ObjectNode part = parts.addObject();
                part.put("type", "text");
                part.put("text", tc.text());
            } else if (c instanceof ImageContent ic) {
                Image img = ic.image();
                String url = img.url() != null ? img.url().toString()
                        : "data:" + (img.mimeType() != null ? img.mimeType() : "image/png") + ";base64," + img.base64Data();
                ObjectNode part = parts.addObject();
                part.put("type", "image_url");
                ObjectNode imageUrl = part.putObject("image_url");
                imageUrl.put("url", url);
                if (ic.detailLevel() != null && ic.detailLevel() != ImageContent.DetailLevel.AUTO) {
                    imageUrl.put("detail", ic.detailLevel().name().toLowerCase());
                }
            }
        }
        return parts;
    }

    private static String userText(UserMessage um) {
        if (um.hasSingleText()) return um.singleText();
        StringBuilder sb = new StringBuilder();
        for (Content c : um.contents()) {
            if (c instanceof TextContent tc) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    private Response<AiMessage> send(ObjectNode body) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new HttpError(resp.statusCode(), resp.body());
        }
        return parseResponse(MAPPER.readTree(resp.body()));
    }

    static Response<AiMessage> parseResponse(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");

        String content = msg.path("content").isTextual() ? msg.path("content").asText() : "";
        String reasoning = firstText(msg, "reasoning_content", "reasoning");
        // Local reasoning models (Ollama, LM Studio) often inline their reasoning as <think>...</think>
        Matcher m = THINK_TAG.matcher(content);
        if (m.find()) {
            String inline = m.group(1).strip();
            reasoning = reasoning == null ? inline : reasoning + "\n" + inline;
            content = content.substring(m.end());
        }

        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode fn = tc.path("function");
            JsonNode args = fn.path("arguments");
            calls.add(ToolExecutionRequest.builder()
                    .id(tc.path("id").asText("call_" + calls.size()))
                    .name(fn.path("name").asText())
                    .arguments(args.isTextual() ? args.asText() : args.toString())
                    .build());
        }

        AiMessage ai;
        if (!calls.isEmpty() && !content.isBlank()) {
            ai = AiMessage.from(content, calls);
        } else if (!calls.isEmpty()) {
            ai = AiMessage.from(calls);
        } else {
            ai = AiMessage.from(content);
        }

        ReasoningContext.LAST_REASONING.set(reasoning != null && !reasoning.isBlank() ? reasoning : null);
        if (!calls.isEmpty()) {
            ReasoningContext.rememberForReplay(ai, reasoning);
        }

        JsonNode usage = root.path("usage");
        TokenUsage tokenUsage = usage.isMissingNode() ? null
                : new TokenUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
        return Response.from(ai, tokenUsage, finishReason(choice.path("finish_reason").asText(null)));
    }

    static FinishReason finishReason(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls", "function_call" -> FinishReason.TOOL_EXECUTION;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.path(k);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    static final class HttpError extends IOException {
        final int status;
        final String body;

        HttpError(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }
}

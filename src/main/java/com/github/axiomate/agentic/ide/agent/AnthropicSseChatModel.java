package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContentBlockType;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API with streaming. Non-streaming calls go to LangChain4j's {@code AnthropicChatModel};
 * streaming calls build the same request with {@link AnthropicMapper} (so thinking blocks of tool-calling turns
 * are still sent back) and read the server-sent events directly.
 */
public class AnthropicSseChatModel implements ChatLanguageModel, StreamingChat {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String API_VERSION = "2023-06-01";

    private final ChatLanguageModel delegate;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient http;

    public AnthropicSseChatModel(ChatLanguageModel delegate, String baseUrl, String apiKey, String modelName,
                                 Double temperature, int maxTokens, Duration timeout) {
        this.delegate = delegate;
        String url = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com/v1/" : baseUrl.trim();
        this.baseUrl = url.endsWith("/") ? url : url + "/";
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
        return delegate.generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return delegate.generate(messages, toolSpecification);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return delegate.generate(messages, toolSpecifications);
    }

    @Override
    public Response<AiMessage> generateStreaming(List<ChatMessage> messages, List<ToolSpecification> tools, Sink sink) {
        AnthropicCreateMessageRequest.AnthropicCreateMessageRequestBuilder request = AnthropicCreateMessageRequest.builder()
                .model(modelName)
                .messages(AnthropicMapper.toAnthropicMessages(messages))
                .system(AnthropicMapper.toAnthropicSystemPrompt(messages))
                .maxTokens(maxTokens)
                .temperature(temperature)
                .stream(true);
        if (tools != null && !tools.isEmpty()) request.tools(AnthropicMapper.toAnthropicTools(tools));
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "messages"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("anthropic-version", API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request.build())));
            if (apiKey != null && !apiKey.isBlank()) req.header("x-api-key", apiKey);
            HttpResponse<InputStream> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                String body;
                try (InputStream in = resp.body()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new RuntimeException("HTTP " + resp.statusCode() + " from " + baseUrl + ": " + body);
            }
            if (!resp.headers().firstValue("Content-Type").orElse("").contains("event-stream")) {
                // An Anthropic-compatible server that ignored "stream": read the whole message instead
                JsonNode message;
                try (InputStream in = resp.body()) {
                    message = MAPPER.readTree(in);
                }
                return fromWholeMessage(message, sink);
            }
            Assembler assembler = new Assembler(sink);
            SseReader.read(resp.body(), sink::isCancelled, assembler::accept);
            return assembler.toResponse();
        } catch (IOException e) {
            throw new RuntimeException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Request to " + baseUrl + " was interrupted", e);
        }
    }

    static Response<AiMessage> fromWholeMessage(JsonNode message, Sink sink) throws IOException {
        List<AnthropicContent> contents = new ArrayList<>();
        for (JsonNode block : message.path("content")) {
            try {
                contents.add(MAPPER.treeToValue(block, AnthropicContent.class));
            } catch (IOException | IllegalArgumentException e) {
                // unknown block type: ignore it, as the streaming path does
            }
        }
        AiMessage ai = AnthropicMapper.toAiMessage(contents);
        if (ai.text() != null && !ai.text().isEmpty()) sink.onText(ai.text());
        JsonNode usage = message.path("usage");
        return Response.from(ai, new TokenUsage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0)),
                AnthropicMapper.toFinishReason(message.path("stop_reason").asText(null)));
    }

    /**
     * Builds the content blocks from message_start / content_block_* / message_delta events and forwards text and
     * thinking deltas as they arrive.
     */
    static final class Assembler {
        private final StreamingChat.Sink sink;
        private final Map<Integer, AnthropicContent> blocks = new LinkedHashMap<>();
        private final Map<Integer, StringBuilder> toolJson = new LinkedHashMap<>();
        private int inputTokens;
        private int outputTokens;
        private String stopReason;
        private int textBlocks;

        Assembler(StreamingChat.Sink sink) {
            this.sink = sink;
        }

        void accept(String payload) {
            JsonNode event;
            try {
                event = MAPPER.readTree(payload);
            } catch (IOException e) {
                return;
            }
            switch (event.path("type").asText()) {
                case "message_start" -> {
                    JsonNode usage = event.path("message").path("usage");
                    inputTokens = usage.path("input_tokens").asInt(0);
                    outputTokens = usage.path("output_tokens").asInt(0);
                }
                case "content_block_start" -> {
                    int index = event.path("index").asInt();
                    try {
                        AnthropicContent block = MAPPER.treeToValue(event.path("content_block"), AnthropicContent.class);
                        blocks.put(index, block);
                        if (block.type == AnthropicContentBlockType.TOOL_USE) toolJson.put(index, new StringBuilder());
                        if (block.type == AnthropicContentBlockType.TEXT) {
                            if (block.text == null) block.text = "";
                            // the final message joins text blocks with a newline; stream the same text
                            if (textBlocks++ > 0) sink.onText("\n");
                        }
                        if (block.text != null && !block.text.isEmpty()) sink.onText(block.text);
                        if (block.thinking != null && !block.thinking.isEmpty()) sink.onReasoning(block.thinking);
                    } catch (IOException | IllegalArgumentException e) {
                        // unknown block type (e.g. server tool results): ignore it
                    }
                }
                case "content_block_delta" -> {
                    int index = event.path("index").asInt();
                    AnthropicContent block = blocks.get(index);
                    JsonNode delta = event.path("delta");
                    if (block == null) return;
                    switch (delta.path("type").asText()) {
                        case "text_delta" -> {
                            String t = delta.path("text").asText();
                            block.text = block.text == null ? t : block.text + t;
                            sink.onText(t);
                        }
                        case "thinking_delta" -> {
                            String t = delta.path("thinking").asText();
                            block.thinking = block.thinking == null ? t : block.thinking + t;
                            sink.onReasoning(t);
                        }
                        case "signature_delta" -> {
                            String sig = delta.path("signature").asText();
                            block.signature = block.signature == null ? sig : block.signature + sig;
                        }
                        case "input_json_delta" -> toolJson.computeIfAbsent(index, k -> new StringBuilder())
                                .append(delta.path("partial_json").asText());
                        default -> {
                        }
                    }
                }
                case "message_delta" -> {
                    if (event.path("delta").hasNonNull("stop_reason")) stopReason = event.path("delta").path("stop_reason").asText();
                    JsonNode usage = event.path("usage");
                    if (usage.has("output_tokens")) outputTokens = usage.path("output_tokens").asInt(outputTokens);
                    if (usage.has("input_tokens")) inputTokens = usage.path("input_tokens").asInt(inputTokens);
                }
                case "error" -> throw new RuntimeException("Anthropic stream error: "
                        + event.path("error").path("message").asText(event.path("error").toString()));
                default -> {
                }
            }
        }

        Response<AiMessage> toResponse() {
            List<AnthropicContent> contents = new ArrayList<>();
            for (Map.Entry<Integer, AnthropicContent> e : blocks.entrySet()) {
                AnthropicContent block = e.getValue();
                if (block.type == AnthropicContentBlockType.TOOL_USE) {
                    String json = toolJson.getOrDefault(e.getKey(), new StringBuilder()).toString();
                    try {
                        block.input = json.isBlank() ? (block.input != null ? block.input : Map.of())
                                : MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
                    } catch (IOException ex) {
                        block.input = Map.of(); // cut off mid-call (cancelled or max_tokens)
                    }
                }
                contents.add(block);
            }
            // Same mapping as the non-streaming path: separates thinking, keeps it for replay with tool calls
            AiMessage message = AnthropicMapper.toAiMessage(contents);
            return Response.from(message, new TokenUsage(inputTokens, outputTokens), AnthropicMapper.toFinishReason(stopReason));
        }
    }
}

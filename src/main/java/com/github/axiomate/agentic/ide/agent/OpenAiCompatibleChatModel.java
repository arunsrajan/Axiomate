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
public class OpenAiCompatibleChatModel implements ChatLanguageModel, StreamingChat {

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
    /** Some servers reject stream_options (usage in the last chunk); after the first such error it is not sent. */
    private volatile boolean sendStreamOptions = true;

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
        return execute(includeReasoning -> send(buildRequest(messages, toolSpecifications, includeReasoning)));
    }

    @Override
    public Response<AiMessage> generateStreaming(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications,
                                                 Sink sink) {
        return execute(includeReasoning -> stream(buildRequest(messages, toolSpecifications, includeReasoning), sink));
    }

    private interface Call {
        Response<AiMessage> run(boolean includeReasoning) throws IOException, InterruptedException;
    }

    private Response<AiMessage> execute(Call call) {
        try {
            try {
                return call.run(sendReasoning);
            } catch (HttpError e) {
                if (sendReasoning && e.status == 400 && e.body.toLowerCase().contains("reasoning")) {
                    log.info("Server rejected reasoning_content in the request; retrying without it");
                    sendReasoning = false;
                    return call.run(false);
                }
                throw e;
            }
        } catch (HttpError e) {
            throw new ProviderException("HTTP " + e.status + " from " + baseUrl + ": " + e.body, e);
        } catch (IOException e) {
            throw new ProviderException("Request to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Request to " + baseUrl + " was interrupted", e);
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

    private HttpRequest.Builder request(ObjectNode body) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        return req;
    }

    /**
     * Streams a completion (SSE). The chunks are assembled into the shape of a non-streaming reply and parsed by
     * {@link #parseResponse}, so reasoning, tool calls and usage are handled exactly as in {@link #generate}.
     */
    private Response<AiMessage> stream(ObjectNode body, Sink sink) throws IOException, InterruptedException {
        body.put("stream", true);
        if (sendStreamOptions) body.putObject("stream_options").put("include_usage", true);
        HttpResponse<java.io.InputStream> resp = http.send(request(body).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String error;
            try (java.io.InputStream in = resp.body()) {
                error = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (sendStreamOptions && resp.statusCode() == 400 && error.contains("stream_options")) {
                log.info("Server rejected stream_options; streaming without usage reporting");
                sendStreamOptions = false;
                body.remove("stream_options");
                return stream(body, sink);
            }
            throw new HttpError(resp.statusCode(), error);
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (!contentType.contains("event-stream")) {
            // The server ignored "stream": parse the whole reply and hand the answer over at once
            Response<AiMessage> whole;
            try (java.io.InputStream in = resp.body()) {
                whole = parseResponse(MAPPER.readTree(in));
            }
            String text = whole.content().text();
            if (text != null && !text.isEmpty()) sink.onText(text);
            return whole;
        }
        StreamAssembler assembler = new StreamAssembler(sink);
        sink.onStreamOpened(resp.body());
        SseReader.read(resp.body(), sink::isCancelled, assembler::accept);
        return parseResponse(assembler.toResponseJson());
    }

    /** Collects streamed chat.completion.chunk deltas and forwards answer text and reasoning to the sink. */
    static final class StreamAssembler {
        private final Sink sink;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final java.util.TreeMap<Integer, String[]> toolCalls = new java.util.TreeMap<>(); // id, name, args
        private String finishReason;
        private JsonNode usage;
        private int textEmitted;
        private int inlineThinkEmitted;

        StreamAssembler(Sink sink) {
            this.sink = sink;
        }

        void accept(String payload) {
            JsonNode chunk;
            try {
                chunk = MAPPER.readTree(payload);
            } catch (IOException e) {
                log.debug("Skipping malformed stream chunk: {}", payload);
                return;
            }
            if (chunk.hasNonNull("error")) {
                JsonNode err = chunk.get("error");
                throw new RuntimeException("Stream error: " + (err.has("message") ? err.get("message").asText() : err.toString()));
            }
            if (chunk.path("usage").isObject()) usage = chunk.get("usage");
            JsonNode choice = chunk.path("choices").path(0);
            if (choice.isMissingNode()) return;
            JsonNode delta = choice.path("delta");
            String r = firstText(delta, "reasoning_content", "reasoning");
            if (r != null) {
                reasoning.append(r);
                sink.onReasoning(r);
            }
            if (delta.path("content").isTextual()) {
                content.append(delta.path("content").asText());
                emitContent();
            }
            int position = 0;
            for (JsonNode tc : delta.path("tool_calls")) {
                int index = tc.has("index") ? tc.get("index").asInt() : position;
                position++;
                String[] call = toolCalls.computeIfAbsent(index, k -> new String[]{null, "", ""});
                if (tc.hasNonNull("id") && !tc.get("id").asText().isEmpty()) call[0] = tc.get("id").asText();
                JsonNode fn = tc.path("function");
                if (fn.path("name").isTextual() && call[1].isEmpty()) call[1] = fn.get("name").asText();
                JsonNode args = fn.path("arguments");
                if (args.isTextual()) call[2] += args.asText();
                else if (args.isObject()) call[2] = args.toString();
            }
            if (choice.hasNonNull("finish_reason")) finishReason = choice.get("finish_reason").asText();
        }

        /**
         * Sends new answer text to the sink. A reply that opens with {@code <think>} (local reasoning models) is
         * reasoning until {@code </think>}; the tag text itself is never shown.
         */
        private void emitContent() {
            String full = content.toString();
            String lead = full.stripLeading();
            String visible;
            String think = "";
            if (lead.startsWith("<think>")) {
                int end = lead.indexOf("</think>");
                if (end < 0) {
                    // hold back a possible partial "</think>"
                    think = lead.substring(7, Math.max(7, lead.length() - 8));
                    visible = "";
                } else {
                    think = lead.substring(7, end);
                    visible = lead.substring(end + 8).stripLeading();
                }
            } else if ("<think>".startsWith(lead)) {
                visible = ""; // could still become "<think>"
            } else {
                visible = full;
            }
            if (think.length() > inlineThinkEmitted) {
                sink.onReasoning(think.substring(inlineThinkEmitted));
                inlineThinkEmitted = think.length();
            }
            if (visible.length() > textEmitted) {
                sink.onText(visible.substring(textEmitted));
                textEmitted = visible.length();
            }
        }

        /** The assembled reply in the shape of a non-streaming chat.completion. */
        JsonNode toResponseJson() {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode choice = root.putArray("choices").addObject();
            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");
            message.put("content", content.toString());
            if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
            if (!toolCalls.isEmpty()) {
                ArrayNode calls = message.putArray("tool_calls");
                for (String[] call : toolCalls.values()) {
                    ObjectNode c = calls.addObject();
                    c.put("id", call[0] != null ? call[0] : newCallId());
                    c.put("type", "function");
                    ObjectNode fn = c.putObject("function");
                    fn.put("name", call[1]);
                    fn.put("arguments", call[2].isBlank() ? "{}" : call[2]);
                }
            }
            if (finishReason != null) choice.put("finish_reason", finishReason);
            if (usage != null) root.set("usage", usage);
            return root;
        }
    }

    private Response<AiMessage> send(ObjectNode body) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(request(body).build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new HttpError(resp.statusCode(), resp.body());
        }
        return parseResponse(MAPPER.readTree(resp.body()));
    }

    static Response<AiMessage> parseResponse(JsonNode root) {
        if (root.hasNonNull("error") && root.path("choices").isEmpty()) {
            // Some gateways answer HTTP 200 with an error object instead of choices
            JsonNode err = root.get("error");
            throw new RuntimeException("Provider error: " + (err.isTextual() ? err.asText()
                    : err.has("message") ? err.get("message").asText() : err.toString()));
        }
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
        } else if (content.stripLeading().startsWith("<think>")) {
            // Cut off before </think> (output token limit): it is all reasoning, there is no answer yet
            String inline = content.stripLeading().substring(7).strip();
            reasoning = reasoning == null ? inline : reasoning + "\n" + inline;
            content = "";
        }

        List<ToolExecutionRequest> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode fn = tc.path("function");
            JsonNode args = fn.path("arguments");
            String id = tc.path("id").asText("");
            calls.add(ToolExecutionRequest.builder()
                    .id(id.isBlank() ? newCallId() : id)
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

    /** Ids for servers that omit them; they must stay unique across the conversation. */
    static String newCallId() {
        return "call_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

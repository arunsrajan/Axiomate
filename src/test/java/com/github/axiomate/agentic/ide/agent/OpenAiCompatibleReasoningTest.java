package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The OpenAI-compatible path (DeepSeek, Ollama, vLLM...) must handle reasoning like the Anthropic path:
 * keep reasoning_content, send it back with tool-calling turns, and continue after reasoning-only replies.
 */
class OpenAiCompatibleReasoningTest {

    private static final String PROVIDER = "TEST_DEEPSEEK_OPENAI";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;

    /** A canned HTTP reply: status code + body. */
    record Reply(int status, String body) {
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        ConfigManager.getInstance().saveConfig(cfg);
    }

    static Reply ok(String messageJson, String finishReason) {
        return new Reply(200, "{\"id\":\"c\",\"object\":\"chat.completion\",\"model\":\"deepseek-reasoner\",\"choices\":[{\"index\":0,"
                + "\"message\":" + messageJson + ",\"finish_reason\":\"" + finishReason + "\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}");
    }

    static Reply toolCall(String id, String reasoning) {
        return ok("{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"" + reasoning + "\",\"tool_calls\":[{\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"no_such_tool\",\"arguments\":\"{\\\"input\\\":\\\"a.txt\\\"}\"}}]}", "tool_calls");
    }

    static Reply text(String content) {
        return ok("{\"role\":\"assistant\",\"content\":\"" + content + "\"}", "stop");
    }

    private List<String> startServer(Deque<Reply> replies) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath() + "\n" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply r = replies.isEmpty() ? text("(no more)") : replies.poll();
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        ProviderConfig p = new ProviderConfig(PROVIDER, "CUSTOM", "Fake DeepSeek",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "deepseek-reasoner",
                new ArrayList<>(List.of(new ModelDefinition("deepseek-reasoner", "DeepSeek Reasoner", 128_000, 32_000, List.of("reasoning")))));
        p.setApiKey("sk-test");
        p.setEnabled(true);
        cfg.getProviders().put(PROVIDER, p);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    private String runAgent(List<String> thoughts) throws Exception {
        SessionManager.getInstance().createSession("DeepSeek test", PROVIDER, "deepseek-reasoner", false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        new LangChainAgentService().sendMessage("Read a.txt and summarize", "", "", new AgentListener() {
            public void onToken(String token) { }
            public void onThinking(String thought) { thoughts.add(thought); }
            public void onToolCall(String toolName, String input) { }
            public void onToolResult(String toolName, String output) { }
            public void onComplete(String fullResponse) { result.set(fullResponse); done.countDown(); }
            public void onError(Throwable t) { error.set(t); done.countDown(); }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS));
        if (error.get() != null) fail(error.get());
        return result.get();
    }

    private static JsonNode body(String request) throws Exception {
        return MAPPER.readTree(request.substring(request.indexOf('\n') + 1));
    }

    @Test
    @DisplayName("reasoning_content of a tool-calling turn is sent back with that turn")
    void reasoningReplayedWithToolCalls() throws Exception {
        List<String> requests = startServer(new ConcurrentLinkedDeque<>(List.of(
                toolCall("call_1", "I need to read a.txt first"),
                text("Summary done."))));
        List<String> thoughts = new CopyOnWriteArrayList<>();

        assertEquals("Summary done.", runAgent(thoughts));
        assertTrue(requests.get(0).startsWith("/v1/chat/completions"));
        JsonNode first = body(requests.get(0));
        assertEquals("deepseek-reasoner", first.path("model").asText());

        JsonNode assistant = null;
        for (JsonNode m : body(requests.get(1)).path("messages")) {
            if ("assistant".equals(m.path("role").asText()) && m.has("tool_calls")) assistant = m;
        }
        assertNotNull(assistant, "assistant tool-call turn replayed");
        assertEquals("I need to read a.txt first", assistant.path("reasoning_content").asText());
        assertEquals("call_1", assistant.path("tool_calls").path(0).path("id").asText());
        assertTrue(thoughts.stream().anyMatch(t -> t.contains("I need to read a.txt first")), "reasoning shown in UI");
    }

    @Test
    @DisplayName("A reasoning-only reply cut off by length is continued")
    void continuesAfterReasoningOnly() throws Exception {
        List<String> requests = startServer(new ConcurrentLinkedDeque<>(List.of(
                ok("{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"Long reasoning...\"}", "length"),
                text("Final answer."))));
        assertEquals("Final answer.", runAgent(new CopyOnWriteArrayList<>()));
        assertTrue(requests.get(1).contains("hit the output token limit"));
    }

    @Test
    @DisplayName("Servers that reject reasoning_content get a retry without it")
    void retriesWithoutReasoningOn400() throws Exception {
        List<String> requests = startServer(new ConcurrentLinkedDeque<>(List.of(
                toolCall("call_1", "plan"),
                new Reply(400, "{\"error\":{\"message\":\"unknown field: reasoning_content\"}}"),
                text("Done anyway."))));
        assertEquals("Done anyway.", runAgent(new CopyOnWriteArrayList<>()));
        assertEquals(3, requests.size());
        assertTrue(requests.get(1).contains("reasoning_content"));
        assertFalse(requests.get(2).contains("reasoning_content"), "retried without reasoning_content");
    }

    @Test
    @DisplayName("Tool specifications are sent as OpenAI function tools with a JSON schema")
    void toolsSerialized() {
        var spec = dev.langchain4j.agent.tool.ToolSpecification.builder()
                .name("read_file").description("Read a file")
                .parameters(dev.langchain4j.agent.tool.ToolParameters.builder()
                        .properties(java.util.Map.of("path", java.util.Map.of("type", "string")))
                        .required(List.of("path")).build())
                .build();
        var model = new OpenAiCompatibleChatModel("http://x/v1/", "k", "m", 0.2, null, java.time.Duration.ofSeconds(5));
        JsonNode req = model.buildRequest(List.of(dev.langchain4j.data.message.UserMessage.from("hi")), List.of(spec), true);
        JsonNode fn = req.path("tools").path(0).path("function");
        assertEquals("function", req.path("tools").path(0).path("type").asText());
        assertEquals("read_file", fn.path("name").asText());
        assertEquals("object", fn.path("parameters").path("type").asText());
        assertEquals("string", fn.path("parameters").path("properties").path("path").path("type").asText());
        assertEquals("path", fn.path("parameters").path("required").path(0).asText());
        assertFalse(req.has("max_tokens"), "server default output limit is kept");
    }

    @Test
    @DisplayName("Inline <think> tags from local models are split into reasoning")
    void inlineThinkTags() throws Exception {
        JsonNode root = MAPPER.readTree(ok("{\"role\":\"assistant\",\"content\":\"<think>step 1\\nstep 2</think>\\n\\nThe answer is 4.\"}", "stop").body());
        Response<AiMessage> r = OpenAiCompatibleChatModel.parseResponse(root);
        assertEquals("The answer is 4.", r.content().text());
        assertEquals("step 1\nstep 2", ReasoningContext.takeLast());
        assertEquals(FinishReason.STOP, r.finishReason());
        assertEquals(10, r.tokenUsage().inputTokenCount());
    }
}

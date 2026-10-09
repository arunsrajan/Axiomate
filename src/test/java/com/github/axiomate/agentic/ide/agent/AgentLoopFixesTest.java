package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.router.AutonomousTaskRouter;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import com.github.axiomate.agentic.ide.agent.tools.AgentTool;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.config.TaskType;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopFixesTest {

    private static final String PROVIDER = "TEST_LOOP_FIXES";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final boolean streamingBefore = ConfigManager.getInstance().getConfig().isStreamingEnabled();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        cfg.setStreamingEnabled(streamingBefore);
        ConfigManager.getInstance().saveConfig(cfg);
    }

    /** A fake OpenAI-compatible server; {@code handler} writes the reply for the n-th request (0-based). */
    interface Handler {
        void handle(int n, String body, com.sun.net.httpserver.HttpExchange ex) throws Exception;
    }

    private List<String> startServer(boolean streaming, Handler handler) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            try {
                handler.handle(count.getAndIncrement(), body, ex);
            } catch (Exception e) {
                // client went away
            } finally {
                ex.close();
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        ProviderConfig p = new ProviderConfig(PROVIDER, "CUSTOM", "Fake", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "gpt-4o", new ArrayList<>(List.of(new ModelDefinition("gpt-4o", "GPT-4o", 100_000, 4_096, List.of()))));
        p.setApiKey("k");
        p.setEnabled(true);
        cfg.getProviders().put(PROVIDER, p);
        cfg.setStreamingEnabled(streaming);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    static void json(com.sun.net.httpserver.HttpExchange ex, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static String completion(String messageJson, String finish, int promptTokens) {
        return "{\"choices\":[{\"index\":0,\"message\":" + messageJson + ",\"finish_reason\":\"" + finish + "\"}],"
                + "\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":10}}";
    }

    static String text(String t, int promptTokens) {
        return completion("{\"role\":\"assistant\",\"content\":\"" + t + "\"}", "stop", promptTokens);
    }

    static String toolCall(String name, String argumentsJson, int promptTokens) {
        return completion("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"" + name + "\",\"arguments\":" + MAPPER.valueToTree(argumentsJson) + "}}]}", "tool_calls", promptTokens);
    }

    static final class Recorder implements AgentListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();

        public void onToken(String t) { events.add("token:" + t); }
        public void onThinking(String t) { events.add("thinking:" + t); }
        public void onToolCall(String n, String i) { events.add("tool:" + n + " " + i); }
        public void onToolResult(String n, String o) { events.add("result:" + n); }
        public void onComplete(String r) { result.set(r); events.add("complete"); done.countDown(); }
        public void onError(Throwable t) { events.add("error:" + t.getMessage()); done.countDown(); }
    }

    private AgentSession newSession() {
        return SessionManager.getInstance().createSession("Loop fixes", PROVIDER, "gpt-4o", false);
    }

    @Test
    @DisplayName("Stop, then a new message: the stopped task stays stopped and reports no error")
    void stoppedTaskDoesNotResume() throws Exception {
        CountDownLatch firstArrived = new CountDownLatch(1);
        startServer(false, (n, body, ex) -> {
            if (n == 0) {
                firstArrived.countDown();
                Thread.sleep(1500); // the first answer is slow; the user presses Stop meanwhile
                json(ex, toolCall("echo", "{}", 100));
            } else {
                json(ex, text("Second answer", 100));
            }
        });
        newSession();
        LangChainAgentService service = new LangChainAgentService();
        List<String> toolRuns = new CopyOnWriteArrayList<>();
        service.registerTool(new AgentTool() {
            public String getName() { return "echo"; }
            public String getDescription() { return "echo"; }
            public String execute(String a) { toolRuns.add(a); return "ok"; }
        });
        Recorder first = new Recorder();
        service.sendMessage("first", "", "", first);
        assertTrue(firstArrived.await(10, TimeUnit.SECONDS));
        service.cancelCurrentTask();
        Recorder second = new Recorder();
        service.sendMessage("second", "", "", second);
        assertTrue(second.done.await(20, TimeUnit.SECONDS));
        assertEquals("Second answer", second.result.get());

        Thread.sleep(2500); // give the stopped task time to misbehave
        assertTrue(toolRuns.isEmpty(), "the stopped task must not run its tool call: " + toolRuns);
        assertTrue(first.events.stream().noneMatch(e -> e.startsWith("error:") || e.equals("complete") || e.startsWith("tool:")),
                "stopped task reported: " + first.events);
        AgentSession s = SessionManager.getInstance().getActiveSession();
        assertTrue(s.getMessages().stream().noneMatch(m -> m.getContent().startsWith("⚠️ Error")), "no error saved after Stop");
    }

    @Test
    @DisplayName("Stop closes a stalled stream at once instead of waiting for the server")
    void stopClosesStalledStream() throws Exception {
        startServer(true, (n, body, ex) -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            Thread.sleep(30_000); // stalls
        });
        newSession();
        LangChainAgentService service = new LangChainAgentService();
        Recorder rec = new Recorder();
        service.sendMessage("go", "", "", rec);
        long deadline = System.currentTimeMillis() + 10_000;
        while (rec.events.stream().noneMatch(e -> e.equals("token:Hel")) && System.currentTimeMillis() < deadline) Thread.sleep(20);
        long stopAt = System.currentTimeMillis();
        service.cancelCurrentTask();
        while (service.isBusy() && System.currentTimeMillis() < stopAt + 10_000) Thread.sleep(20);
        Thread.sleep(300);
        assertTrue(System.currentTimeMillis() - stopAt < 5_000, "stopping took too long");
        assertTrue(rec.events.stream().noneMatch(e -> e.startsWith("error:")), rec.events.toString());
    }

    @Test
    @DisplayName("The context meter shows the conversation size, not the sum of every request")
    void contextMeterIsNotCumulative() throws Exception {
        startServer(false, (n, body, ex) -> json(ex, n == 0 ? toolCall("missing_tool", "{}", 40_000) : text("Done", 41_000)));
        AgentSession s = newSession();
        Recorder rec = new Recorder();
        new LangChainAgentService().sendMessage("go", "", "", rec);
        assertTrue(rec.done.await(20, TimeUnit.SECONDS));
        TokenTracker t = s.getTokenTracker();
        assertTrue(t.getContextTokens() >= 41_010 && t.getContextTokens() < 42_000,
                "context is the last request (41,000 in + 10 out) plus the saved reply, not 81,000+: " + t.getContextTokens());
        assertTrue(t.getTotalTokens() >= 81_020, "tokens used so far still add up: " + t.getTotalTokens());
        assertTrue(t.getUsagePercentage() < 50, "41k of 100k, not over 80%");

        TokenTracker legacy = MAPPER.readValue("{\"promptTokens\":500000,\"completionTokens\":1000,\"totalTokens\":501000,\"maxContextTokens\":100000}",
                TokenTracker.class);
        assertFalse(legacy.isThresholdReached(0.95), "old cumulative totals must not trigger compression");
    }

    @Test
    @DisplayName("Compressed history is sent to the model instead of being dropped")
    void compressedSummaryReachesModel() throws Exception {
        List<String> requests = startServer(false, (n, body, ex) -> json(ex, text("ok", 100)));
        AgentSession s = newSession();
        s.addMessage(new AgentMessage(AgentRole.SYSTEM, "### Condensed Context Summary\n- USER: we chose PostgreSQL 16"));
        Recorder rec = new Recorder();
        new LangChainAgentService().sendMessage("which database?", "", "", rec);
        assertTrue(rec.done.await(20, TimeUnit.SECONDS));
        JsonNode system = MAPPER.readTree(requests.get(0)).path("messages").get(0);
        assertEquals("system", system.path("role").asText());
        assertTrue(system.path("content").asText().contains("we chose PostgreSQL 16"), system.toString());
    }

    @Test
    @DisplayName("Tool input sent as a JSON object reaches the tool intact")
    void objectToolInput() throws Exception {
        startServer(false, (n, body, ex) -> json(ex, n == 0
                ? toolCall("fs", "{\"input\":{\"action\":\"list\",\"path\":\"src\"}}", 100) : text("listed", 100)));
        newSession();
        LangChainAgentService service = new LangChainAgentService();
        AtomicReference<String> got = new AtomicReference<>();
        service.registerTool(new AgentTool() {
            public String getName() { return "fs"; }
            public String getDescription() { return "fs"; }
            public String execute(String a) { got.set(a); return "ok"; }
        });
        Recorder rec = new Recorder();
        service.sendMessage("list src", "", "", rec);
        assertTrue(rec.done.await(20, TimeUnit.SECONDS));
        assertEquals("list", MAPPER.readTree(got.get()).path("action").asText(), "tool got: " + got.get());
    }

    @Test
    @DisplayName("No routing note when the session's own model is used")
    void noRoutingNoiseForManualSelection() throws Exception {
        startServer(false, (n, body, ex) -> json(ex, text("ok", 100)));
        newSession();
        Recorder rec = new Recorder();
        new LangChainAgentService().sendMessage("hello", "", "", rec);
        assertTrue(rec.done.await(20, TimeUnit.SECONDS));
        assertTrue(rec.events.stream().noneMatch(e -> e.startsWith("thinking:🎯")), rec.events.toString());
    }

    @Test
    @DisplayName("An unlisted model's max_tokens is not taken from another model")
    void unlistedModelMaxOutput() {
        ProviderConfig p = new ProviderConfig("P", "ANTHROPIC", "P", null, "claude-opus-4-1",
                new ArrayList<>(List.of(new ModelDefinition("claude-opus-4-1", "Opus", 200_000, 32_000, List.of()))));
        assertEquals(32_000, UniversalChatModelFactory.resolveMaxOutputTokens(p, "claude-opus-4-1", 8_192));
        assertEquals(8_192, UniversalChatModelFactory.resolveMaxOutputTokens(p, "claude-haiku-4-5", 8_192));
    }

    @Test
    @DisplayName("Task classification matches whole words")
    void classifierUsesWholeWords() {
        assertEquals(TaskType.GENERAL, AutonomousTaskRouter.classifyTask("Show the latest release notes"));
        assertEquals(TaskType.GENERAL, AutonomousTaskRouter.classifyTask("Add a prefix to every log line"));
        assertEquals(TaskType.GENERAL, AutonomousTaskRouter.classifyTask("Merge the trunk and return early"));
        assertEquals(TaskType.GENERATE_TESTS, AutonomousTaskRouter.classifyTask("Write unit tests for the parser"));
        assertEquals(TaskType.DEBUG_FIX, AutonomousTaskRouter.classifyTask("Why does it throw IllegalStateException?"));
        assertEquals(TaskType.TERMINAL_TOOL, AutonomousTaskRouter.classifyTask("Run mvn package"));
        assertEquals(TaskType.REFACTOR, AutonomousTaskRouter.classifyTask("Refactoring the architecture"));
    }

    @Test
    @DisplayName("OpenAI-compatible parsing: errors in a 200 reply, unclosed <think>, missing tool-call ids")
    void openAiParsingEdgeCases() throws Exception {
        RuntimeException err = assertThrows(RuntimeException.class, () -> OpenAiCompatibleChatModel.parseResponse(
                MAPPER.readTree("{\"error\":{\"message\":\"Rate limit exceeded\"}}")));
        assertTrue(err.getMessage().contains("Rate limit exceeded"));

        Response<AiMessage> cut = OpenAiCompatibleChatModel.parseResponse(MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"content\":\"<think>still planning the\"},\"finish_reason\":\"length\"}]}"));
        assertEquals("", cut.content().text(), "an unfinished <think> block is not an answer");
        assertEquals("still planning the", ReasoningContext.takeLast());

        Response<AiMessage> calls = OpenAiCompatibleChatModel.parseResponse(MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}},"
                        + "{\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));
        Response<AiMessage> again = OpenAiCompatibleChatModel.parseResponse(MAPPER.readTree(
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));
        List<String> ids = new ArrayList<>();
        calls.content().toolExecutionRequests().forEach(r -> ids.add(r.id()));
        again.content().toolExecutionRequests().forEach(r -> ids.add(r.id()));
        assertEquals(3, ids.stream().distinct().count(), "generated ids are unique: " + ids);
        assertTrue(ids.stream().noneMatch(String::isBlank));
        ReasoningContext.clear();
    }
}

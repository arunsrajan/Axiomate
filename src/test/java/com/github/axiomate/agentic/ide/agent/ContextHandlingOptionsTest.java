package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.SessionTask;
import com.github.axiomate.agentic.ide.agent.tools.AgentTool;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-provider context options (compact older tool outputs, cap tool outputs, stable prompt prefix): what each
 * changes when ticked, and that nothing changes when it is not.
 */
class ContextHandlingOptionsTest {

    private static final String PROVIDER = "TEST_CONTEXT_OPTIONS";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    /** What the fake server answers to GET /models; null: 404, like a server without that endpoint. */
    private String modelsReply;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        ContextWindowProbe.forget();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        ConfigManager.getInstance().saveConfig(cfg);
    }

    // ---------------------------------------------------------------- the pieces

    @Test
    @DisplayName("Capping keeps a long output's beginning and end, says what was cut, and leaves short ones alone")
    void capToolOutput() {
        assertEquals("short", LangChainAgentService.capToolOutput("short", 100));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5_000; i++) sb.append("line ").append(i).append('\n');
        String capped = LangChainAgentService.capToolOutput(sb.toString(), 9_000);
        assertTrue(capped.startsWith("line 0\nline 1\n"));
        assertTrue(capped.endsWith("line 4999\n"), "the end (errors, summaries) is kept");
        assertTrue(capped.contains("characters (") && capped.contains("cut to fit the context"), capped.substring(5_900, 6_200));
        assertTrue(capped.length() < 9_300);
        assertEquals(8_000, LangChainAgentService.toolOutputCap(4_096), "lower bound");
        assertEquals(26_214, LangChainAgentService.toolOutputCap(32_768), "about a fifth of the window, in characters");
        assertEquals(40_000, LangChainAgentService.toolOutputCap(200_000), "upper bound");
    }

    private static List<ChatMessage> conversation(int toolResults, int charsEach) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        messages.add(new UserMessage("go"));
        for (int i = 0; i < toolResults; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder().id("c" + i).name("dump").arguments("{\"i\":" + i + "}").build();
            messages.add(AiMessage.from(List.of(req)));
            messages.add(ToolExecutionResultMessage.from(req, ("output " + i + " ").repeat(charsEach / 10)));
        }
        return messages;
    }

    private static List<String> resultTexts(List<ChatMessage> messages) {
        return messages.stream().filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text()).toList();
    }

    @Test
    @DisplayName("Compaction replaces the oldest outputs until the request fits, keeps the latest three and every call's id")
    void compactToolResults() {
        List<ChatMessage> messages = conversation(8, 4_000);   // 8 x ~1,000 tokens
        assertEquals(0, LangChainAgentService.compactToolResults(messages, 100_000, 3), "under the budget: untouched");

        int compacted = LangChainAgentService.compactToolResults(messages, 1_000, 3);
        List<String> texts = resultTexts(messages);
        assertEquals(5, compacted, "oldest first, until it fits or only the latest three are left");
        for (int i = 0; i < 5; i++) {
            assertTrue(texts.get(i).startsWith("[Output of dump removed to keep the request within the model's context"), texts.get(i));
        }
        for (int i = 5; i < 8; i++) assertTrue(texts.get(i).startsWith("output " + i), "the latest outputs stay whole");
        assertEquals("c0", ((ToolExecutionResultMessage) messages.get(3)).id(), "each result still answers its call");
        assertEquals(0, LangChainAgentService.compactToolResults(messages, 1_000, 3), "notes are not replaced again");

        List<ChatMessage> some = conversation(8, 4_000);
        // ~7,200 tokens of outputs at ~900 each: two notes bring it under 6,000, and it stops there
        assertEquals(2, LangChainAgentService.compactToolResults(some, 6_000, 3), "stops as soon as the request fits");
    }

    @Test
    @DisplayName("Without the stable-prefix option the mode instructions are as before; with it the task list moves out")
    void instructionsAndTaskList() {
        AgentSession s = new AgentSession("Tasks", "OPENAI", "gpt-4o", 128_000);
        s.getTasks().add(new SessionTask("Write tests", SessionTask.Status.IN_PROGRESS, "agent"));
        String all = LangChainAgentService.executionModeInstructions(s);
        assertTrue(all.contains("- Current task list (0/1 done):\n1. [~] Write tests"), all);
        assertEquals(all, LangChainAgentService.executionModeInstructions(s, true));
        assertFalse(LangChainAgentService.executionModeInstructions(s, false).contains("Write tests"));
        assertEquals("## Current task list (0/1 done)\n1. [~] Write tests", LangChainAgentService.taskListContext(s));
        assertEquals("", LangChainAgentService.taskListContext(new AgentSession("Empty", "OPENAI", "gpt-4o", 128_000)));
    }

    @Test
    @DisplayName("The Colibri preset: local OpenAI-compatible server, disabled until enabled, every option ticked; added once")
    void colibriPreset() {
        ProviderConfig c = IdeConfig.colibriPreset();
        assertEquals("CUSTOM", c.getProviderType());
        assertEquals("http://127.0.0.1:8000/v1", c.getBaseUrl());
        assertEquals("local", c.getApiKey(), "any non-empty key; Colibri does not check it unless COLI_API_KEY is set");
        assertFalse(c.isEnabled(), "nothing calls a server the user has not started");
        assertTrue(c.isCompactToolResults() && c.isCapToolOutputs() && c.isStablePromptPrefix());
        assertEquals(65_536, c.findExactModel(c.getDefaultModel()).getMaxContextTokens());

        IdeConfig cfg = new IdeConfig();
        assertTrue(cfg.getProviders().containsKey(IdeConfig.COLIBRI_PROVIDER_ID), "new configs have it");
        assertFalse(cfg.ensureColibriProvider(), "added once");
        cfg.getProviders().remove(IdeConfig.COLIBRI_PROVIDER_ID);
        assertTrue(cfg.ensureColibriProvider(), "configs saved before it existed get it");
        ProviderConfig other = cfg.getProvider("OPENAI");
        assertFalse(other.isCompactToolResults() || other.isCapToolOutputs() || other.isStablePromptPrefix(),
                "other providers keep working as before");
    }

    // ---------------------------------------------------------------- through the agent loop

    private List<String> startServer(int contextWindow, boolean compact, boolean cap, boolean stable,
                                     IntFunction<String> replies) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if ("GET".equals(ex.getRequestMethod()) && ex.getRequestURI().getPath().endsWith("/models")) {
                try {
                    if (modelsReply != null) AgentLoopFixesTest.json(ex, modelsReply);
                    else ex.sendResponseHeaders(404, -1);
                } catch (Exception ignored) {
                    // client went away
                } finally {
                    ex.close();
                }
                return;
            }
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                String reply = replies.apply(count.getAndIncrement());
                if (reply.startsWith(ERROR_400)) {
                    byte[] b = reply.substring(ERROR_400.length()).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(400, b.length);
                    ex.getResponseBody().write(b);
                } else {
                    AgentLoopFixesTest.json(ex, reply);
                }
            } catch (Exception ignored) {
                // client went away
            } finally {
                ex.close();
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        ProviderConfig p = new ProviderConfig(PROVIDER, "CUSTOM", "Fake", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "local-model", new ArrayList<>(List.of(new ModelDefinition("local-model", "Local", contextWindow, 2_048, List.of()))));
        p.setApiKey("local");
        p.setEnabled(true);
        p.setCompactToolResults(compact);
        p.setCapToolOutputs(cap);
        p.setStablePromptPrefix(stable);
        cfg.getProviders().put(PROVIDER, p);
        cfg.setStreamingEnabled(false);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    /** A tool whose output is {@code chars} long. */
    private static AgentTool dump(int chars) {
        return new AgentTool() {
            public String getName() { return "dump"; }
            public String getDescription() { return "dump: prints a lot"; }
            public String execute(String arguments) {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                while (sb.length() < chars) sb.append("row ").append(n++).append(' ').append(arguments).append('\n');
                return sb.substring(0, chars);
            }
        };
    }

    private static final String ERROR_400 = "!400 ";

    private static List<String> run(AgentSession session, AgentTool tool, String prompt) throws Exception {
        List<String> thoughts = runAllowingErrors(session, tool, prompt);
        assertFalse(thoughts.stream().anyMatch(t -> t.startsWith("error ")), thoughts.toString());
        return thoughts;
    }

    private static List<String> runAllowingErrors(AgentSession session, AgentTool tool, String prompt) throws Exception {
        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(tool);
        List<String> thoughts = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        service.sendMessage(prompt, "", "", new AgentListener() {
            public void onToken(String t) { }
            public void onThinking(String t) { thoughts.add(t); }
            public void onToolCall(String n, String i) { }
            public void onToolResult(String n, String o) { }
            public void onComplete(String r) { done.countDown(); }
            public void onError(Throwable t) { thoughts.add("error " + t.getMessage()); done.countDown(); }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS));
        return thoughts;
    }

    private static String toolResultSent(String request) throws Exception {
        for (JsonNode m : MAPPER.readTree(request).path("messages")) {
            if ("tool".equals(m.path("role").asText())) return m.path("content").asText();
        }
        return null;
    }

    @Test
    @DisplayName("Cap ticked: the model gets a cut copy of a huge output; not ticked: it gets all of it, as before")
    void capInTheLoop() throws Exception {
        for (boolean cap : new boolean[]{true, false}) {
            if (server != null) server.stop(0);
            List<String> requests = startServer(65_536, false, cap, false, n -> n == 0
                    ? AgentLoopFixesTest.toolCall("dump", "{}", 10) : AgentLoopFixesTest.text("done", 10));
            AgentSession s = SessionManager.getInstance().createSession("Cap " + cap, PROVIDER, "local-model", false);
            run(s, dump(100_000), "dump it");
            String sent = toolResultSent(requests.get(1));
            if (cap) {
                assertTrue(sent.length() <= 40_300 && sent.contains("cut to fit the context"), "capped: " + sent.length());
            } else {
                assertEquals(100_000, sent.length(), "unchanged when the option is off");
            }
            String recorded = s.getMessages().stream().filter(m -> m.getRole() == AgentRole.TOOL).findFirst().orElseThrow().getContent();
            assertEquals(100_000, recorded.length(), "the transcript always keeps the full output");
        }
    }

    @Test
    @DisplayName("Compaction ticked: a long run's older outputs become notes; not ticked: every output is resent whole")
    void compactionInTheLoop() throws Exception {
        for (boolean compact : new boolean[]{true, false}) {
            if (server != null) server.stop(0);
            List<String> requests = startServer(8_000, compact, false, false, n -> n < 6
                    ? AgentLoopFixesTest.toolCall("dump", "{\"step\":" + n + "}", 10) : AgentLoopFixesTest.text("done", 10));
            AgentSession s = SessionManager.getInstance().createSession("Compact " + compact, PROVIDER, "local-model", false);
            List<String> thoughts = run(s, dump(6_000), "dump repeatedly");
            String last = requests.get(requests.size() - 1);
            boolean hasNotes = last.contains("removed to keep the request within the model's context");
            assertEquals(compact, hasNotes, "notes only when the option is ticked");
            assertEquals(compact, thoughts.stream().anyMatch(t -> t.startsWith("🗜 Removed")), thoughts.toString());
            if (compact) {
                assertTrue(last.length() < 6 * 6_000, "the request shrank: " + last.length());
                assertTrue(last.contains("{\\\"step\\\":5}"), "the latest output is whole");
            }
        }
    }

    private static String role(String request, String role) throws Exception {
        for (JsonNode m : MAPPER.readTree(request).path("messages")) {
            if (role.equals(m.path("role").asText())) return m.path("content").asText();
        }
        return null;
    }

    private static String lastUser(String request) throws Exception {
        String last = null;
        for (JsonNode m : MAPPER.readTree(request).path("messages")) {
            if ("user".equals(m.path("role").asText())) last = m.path("content").asText();
        }
        return last;
    }

    @Test
    @DisplayName("Stable prefix ticked: the system prompt stays identical across turns, the task list rides in the request")
    void stablePrefixInTheLoop() throws Exception {
        for (boolean stable : new boolean[]{true, false}) {
            if (server != null) server.stop(0);
            List<String> requests = startServer(65_536, false, false, stable, n -> AgentLoopFixesTest.text("ok", 10));
            AgentSession s = SessionManager.getInstance().createSession("Stable " + stable, PROVIDER, "local-model", false);
            s.getTasks().add(new SessionTask("First step", SessionTask.Status.IN_PROGRESS, "agent"));
            run(s, dump(10), "first");
            s.getTasks().get(0).setStatus(SessionTask.Status.COMPLETED);
            s.getTasks().add(new SessionTask("Second step", SessionTask.Status.PENDING, "agent"));
            run(s, dump(10), "second");

            String system1 = role(requests.get(0), "system");
            String system2 = role(requests.get(1), "system");
            if (stable) {
                assertEquals(system1, system2, "the beginning of the prompt does not change between turns");
                assertFalse(system2.contains("Current task list"));
                assertFalse(system2.contains("Agentic AI Memory"), "memory context moved out of the system prompt too");
                String request = lastUser(requests.get(1));
                assertTrue(request.contains("## Current task list (1/2 done)\n1. [x] First step\n2. [ ] Second step"), request);
                assertTrue(request.contains("Agentic AI Memory") && request.contains("User Request: second"), request);
            } else {
                assertNotEquals(system1, system2, "as before: the task list is in the system prompt");
                assertTrue(system2.contains("- Current task list (1/2 done):"));
                assertTrue(lastUser(requests.get(1)).startsWith("User Request: second"));
            }
        }
    }

    // ---------------------------------------------------------------- fitting the request to the context

    @Test
    @DisplayName("Fitting leaves out the oldest turns first and stops once the request fits; tool calls stay paired")
    void fitDropsOldestTurns() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        for (int i = 0; i < 6; i++) {
            messages.add(new UserMessage("question " + i + " " + "q".repeat(4_000)));
            messages.add(new AiMessage("answer " + i + " " + "a".repeat(4_000)));
        }
        int request = messages.size();
        messages.add(new UserMessage("User Request: now"));
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("c1").name("dump").arguments("{}").build();
        messages.add(AiMessage.from(List.of(req)));
        messages.add(ToolExecutionResultMessage.from(req, "result"));

        LangChainAgentService.Fit same = LangChainAgentService.fitToContext(messages, new ArrayList<>(), 100_000, request);
        assertEquals("", same.note(), "fits: untouched");
        assertEquals(16, messages.size());

        LangChainAgentService.Fit fit = LangChainAgentService.fitToContext(messages, new ArrayList<>(), 4_100, request);
        assertEquals("8 earlier messages left out", fit.note());
        assertTrue(((UserMessage) messages.get(1)).singleText().startsWith("question 4"), "the latest turns stay, from a question");
        assertEquals("User Request: now", ((UserMessage) messages.get(fit.requestIndex())).singleText());
        assertInstanceOf(ToolExecutionResultMessage.class, messages.get(messages.size() - 1), "the tool call keeps its result");
        assertEquals(8, messages.size());
    }

    @Test
    @DisplayName("Fitting then shortens long tool descriptions, then cuts the request keeping its end (the question)")
    void fitShortensToolsThenRequest() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new SystemMessage("system"),
                new UserMessage("Context Code:\n" + "x".repeat(40_000) + "\nUser Request: hi")));
        List<dev.langchain4j.agent.tool.ToolSpecification> specs = new ArrayList<>(List.of(
                dev.langchain4j.agent.tool.ToolSpecification.builder().name("mcp").description("schema ".repeat(5_000)).build(),
                dev.langchain4j.agent.tool.ToolSpecification.builder().name("small").description("reads files").build()));
        LangChainAgentService.Fit fit = LangChainAgentService.fitToContext(messages, specs, 3_000, 1);
        assertTrue(fit.note().startsWith("tool descriptions shortened, the request cut to"), fit.note());
        assertTrue(specs.get(0).description().length() <= LangChainAgentService.SHORT_TOOL_DESCRIPTION + 2);
        assertEquals("reads files", specs.get(1).description(), "short descriptions are left alone");
        String request = ((UserMessage) messages.get(1)).singleText();
        assertTrue(request.endsWith("User Request: hi") && request.length() < 12_000, request.length() + "");
        assertTrue(LangChainAgentService.estimateTokens(messages) + LangChainAgentService.estimateToolTokens(specs) <= 3_100);
        assertEquals(25_446, LangChainAgentService.budgetFor(40_000, 8_192), "room for the answer, and a margin for the estimate");
    }

    @Test
    @DisplayName("The real window: read from GET /models, and from a too-long request's error (Colibri, vLLM)")
    void probeParsing() throws Exception {
        JsonNode models = MAPPER.readTree("{\"data\":[{\"id\":\"a\",\"context_length\":40000},{\"id\":\"b\",\"max_model_len\":8192}]}");
        assertEquals(40_000, ContextWindowProbe.windowIn(models, "A"));
        assertEquals(8_192, ContextWindowProbe.windowIn(models, "b"));
        assertEquals(0, ContextWindowProbe.windowIn(models, "c"), "several models and none matches: unknown");
        assertEquals(32_768, ContextWindowProbe.windowIn(MAPPER.readTree("{\"data\":[{\"id\":\"x\",\"context_length\":32768}]}"), "y"));
        Exception colibri = new RuntimeException("Error calling provider", new RuntimeException("HTTP 400: {\"error\":{\"message\":"
                + "\"This model's maximum context length is 40000 tokens, however your messages resulted in at least 47368 tokens.\"}}"));
        assertEquals(40_000, ContextWindowProbe.limitIn(colibri));
        assertEquals(47_368, ContextWindowProbe.usedIn(colibri));
        Exception vllm = new RuntimeException("This model's maximum context length is 32768 tokens. However, you requested 33000 tokens");
        assertEquals(32_768, ContextWindowProbe.limitIn(vllm));
        assertEquals(33_000, ContextWindowProbe.usedIn(vllm));
        assertEquals(0, ContextWindowProbe.limitIn(new RuntimeException("rate limited")));
    }

    @Test
    @DisplayName("Memories in the request can be cut to a length; whole by default, as before")
    void memoryItemCap() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("memories", ".json");
        java.nio.file.Files.delete(file);
        var store = new com.github.axiomate.agentic.ide.agent.memory.JsonAgentMemoryStore(file);
        store.addMemory(new com.github.axiomate.agentic.ide.agent.memory.MemoryItem(
                com.github.axiomate.agentic.ide.agent.memory.MemoryType.PROJECT_RULE, "Huge rule", "rule ".repeat(10_000)));
        assertTrue(store.getRelevantContext("huge rule").length() > 50_000);
        String cut = store.getRelevantContext("huge rule", 1_500);
        assertTrue(cut.length() < 2_000 && cut.contains("more characters; agent_memory search can show the rest"), cut);
        java.nio.file.Files.deleteIfExists(file);
    }

    /** A tool with an MCP-sized description (its schema). */
    private static AgentTool bigTool(int descriptionChars) {
        return new AgentTool() {
            public String getName() { return "big_mcp_tool"; }
            public String getDescription() { return "schema ".repeat(descriptionChars / 7); }
            public String execute(String arguments) { return "ok"; }
        };
    }

    @Test
    @DisplayName("Compaction ticked: \"hi\" fits the window the server reports; not ticked: sent as before")
    void hiFitsTheServedWindow() throws Exception {
        for (boolean compact : new boolean[]{true, false}) {
            if (server != null) server.stop(0);
            ContextWindowProbe.forget();
            modelsReply = "{\"data\":[{\"id\":\"local-model\",\"context_length\":8000}]}";
            List<String> requests = startServer(65_536, compact, false, false, n -> AgentLoopFixesTest.text("hello", 10));
            AgentSession s = SessionManager.getInstance().createSession("Hi " + compact, PROVIDER, "local-model", false);
            List<String> thoughts = run(s, bigTool(30_000), "hi");
            String sent = requests.get(0);
            if (compact) {
                assertTrue(thoughts.stream().anyMatch(t -> t.contains("with a 8,000-token context (configured: 65,536)")), thoughts.toString());
                assertTrue(thoughts.stream().anyMatch(t -> t.startsWith("🗜 Fitted the request")), thoughts.toString());
                assertTrue(sent.length() < 4 * 8_000, "fits the served window: " + sent.length());
                assertTrue(sent.contains("in the form the tool's description gives"), "the description is not sent twice");
            } else {
                assertTrue(sent.length() > 60_000, "the description twice, as before: " + sent.length());
                assertFalse(thoughts.stream().anyMatch(t -> t.startsWith("🗜") || t.startsWith("📏")), thoughts.toString());
            }
        }
    }

    @Test
    @DisplayName("Compaction ticked: a context-length error teaches the real window, and the fitted request is retried once")
    void learnsTheWindowFromTheError() throws Exception {
        String tooLong = ERROR_400 + "{\"error\":{\"message\":\"This model's maximum context length is 6000 tokens, however "
                + "your messages resulted in at least 9000 tokens.\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}";
        for (boolean compact : new boolean[]{true, false}) {
            if (server != null) server.stop(0);
            ContextWindowProbe.forget();
            modelsReply = null;
            List<String> requests = startServer(65_536, compact, false, false, n -> n == 0 ? tooLong : AgentLoopFixesTest.text("hello", 10));
            AgentSession s = SessionManager.getInstance().createSession("Learn " + compact, PROVIDER, "local-model", false);
            List<String> thoughts = runAllowingErrors(s, bigTool(20_000), "hi");
            boolean failed = thoughts.stream().anyMatch(t -> t.startsWith("error "));
            if (compact) {
                assertFalse(failed, thoughts.toString());
                assertTrue(thoughts.stream().anyMatch(t -> t.contains("runs with a 6,000-token context and the request was 9,000 tokens")), thoughts.toString());
                assertEquals(2, requests.size());
                assertTrue(requests.get(1).length() < requests.get(0).length() / 2, requests.get(1).length() + " vs " + requests.get(0).length());
            } else {
                assertTrue(failed, "as before: the error is reported");
                assertEquals(1, requests.size());
            }
        }
    }
}

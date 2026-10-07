package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.FinishReason;
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
 * Reproduces "the agent stops at reasoning": a thinking model spends its output budget on reasoning and returns
 * no text or tool call. The agent must ask it to continue instead of ending the task with the reasoning.
 */
class ReasoningContinuationTest {

    private static final String PROVIDER = "TEST_REASONING_ANTHROPIC";
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        cfg.setMaxAgentIterations(IdeConfig.DEFAULT_MAX_AGENT_ITERATIONS);
        ConfigManager.getInstance().saveConfig(cfg);
    }

    private static String message(String contentJson, String stopReason) {
        return "{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"reasoner\",\"content\":" + contentJson
                + ",\"stop_reason\":\"" + stopReason + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
    }

    private List<String> startFakeAnthropic(Deque<String> responses) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = responses.isEmpty() ? message("[{\"type\":\"text\",\"text\":\"(no more)\"}]", "end_turn") : responses.poll();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        ProviderConfig p = new ProviderConfig(PROVIDER, "ANTHROPIC", "Fake Anthropic",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "reasoner",
                new ArrayList<>(List.of(new ModelDefinition("reasoner", "Reasoner", 200_000, 32_000, List.of("reasoning")))));
        p.setApiKey("test-key");
        p.setEnabled(true);
        cfg.getProviders().put(PROVIDER, p);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    private String runAgent(List<String> thoughts) throws Exception {
        AgentSession s = SessionManager.getInstance().createSession("Reasoning test", PROVIDER, "reasoner", false);
        assertNotNull(s);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        new LangChainAgentService().sendMessage("What is the answer?", "", "", new AgentListener() {
            public void onToken(String token) { }
            public void onThinking(String thought) { thoughts.add(thought); }
            public void onToolCall(String toolName, String input) { }
            public void onToolResult(String toolName, String output) { }
            public void onComplete(String fullResponse) { result.set(fullResponse); done.countDown(); }
            public void onError(Throwable t) { error.set(t); done.countDown(); }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "agent finished");
        if (error.get() != null) fail(error.get());
        return result.get();
    }

    @Test
    @DisplayName("A reasoning-only step cut off at max_tokens is continued until the model answers")
    void continuesAfterReasoningOnlyStep() throws Exception {
        Deque<String> responses = new ConcurrentLinkedDeque<>(List.of(
                message("[{\"type\":\"thinking\",\"thinking\":\"The user wants the answer. Step one...\",\"signature\":\"sig\"}]", "max_tokens"),
                message("[{\"type\":\"text\",\"text\":\"The answer is 42.\"}]", "end_turn")));
        List<String> requests = startFakeAnthropic(responses);
        List<String> thoughts = new CopyOnWriteArrayList<>();

        String answer = runAgent(thoughts);

        assertEquals("The answer is 42.", answer, "the agent does not stop at the reasoning");
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).replaceAll("\\s+", "").contains("\"max_tokens\":32000"),
                "configured Max Output is sent instead of LangChain4j's 1024 default");
        assertTrue(requests.get(1).contains("hit the output token limit"), "continuation prompt sent");
        assertTrue(requests.get(1).contains("Step one"), "earlier reasoning is carried over");
        assertTrue(thoughts.stream().anyMatch(t -> t.contains("Step one")), "reasoning shown in the UI");
        assertTrue(thoughts.stream().anyMatch(t -> t.contains("Asking it to continue")));
    }

    @Test
    @DisplayName("If the model keeps returning only reasoning, the agent stops after a bounded number of retries with a hint")
    void boundedRetries() throws Exception {
        String thinkingOnly = message("[{\"type\":\"thinking\",\"thinking\":\"Still thinking\",\"signature\":\"s\"}]", "end_turn");
        List<String> requests = startFakeAnthropic(new ConcurrentLinkedDeque<>(List.of(thinkingOnly, thinkingOnly, thinkingOnly, thinkingOnly)));
        String answer = runAgent(new CopyOnWriteArrayList<>());
        assertEquals(1 + LangChainAgentService.MAX_REASONING_CONTINUATIONS, requests.size());
        assertTrue(answer.contains("Still thinking"));
        assertTrue(answer.contains("Max Output"));
    }

    @Test
    @DisplayName("Step classification: tool calls and text complete a step; bare reasoning does not")
    void classify() {
        var tool = AiMessage.from(ToolExecutionRequest.builder().id("1").name("t").arguments("{}").build());
        assertEquals(LangChainAgentService.ReasoningOutcome.COMPLETE, LangChainAgentService.classifyStep(tool, FinishReason.LENGTH, "x"));
        assertEquals(LangChainAgentService.ReasoningOutcome.COMPLETE, LangChainAgentService.classifyStep(AiMessage.from("hi"), FinishReason.STOP, "x"));
        assertEquals(LangChainAgentService.ReasoningOutcome.TRUNCATED, LangChainAgentService.classifyStep(AiMessage.from(""), FinishReason.LENGTH, null));
        assertEquals(LangChainAgentService.ReasoningOutcome.REASONING_ONLY, LangChainAgentService.classifyStep(AiMessage.from(""), FinishReason.STOP, "x"));
        assertEquals(LangChainAgentService.ReasoningOutcome.COMPLETE, LangChainAgentService.classifyStep(AiMessage.from(""), FinishReason.STOP, null));
    }

    private static String toolUse(String id, String thinking) {
        String think = thinking == null ? "" : "{\"type\":\"thinking\",\"thinking\":\"" + thinking + "\",\"signature\":\"sig-" + id + "\"},";
        return message("[" + think + "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"no_such_tool\",\"input\":{\"path\":\"a.txt\"}}]", "tool_use");
    }

    @Test
    @DisplayName("Reasoning of a tool-calling turn is sent back with that turn (with its signature)")
    void thinkingReplayedWithToolTurn() throws Exception {
        List<String> requests = startFakeAnthropic(new ConcurrentLinkedDeque<>(List.of(
                toolUse("t1", "I should read a.txt first"),
                message("[{\"type\":\"text\",\"text\":\"Done.\"}]", "end_turn"))));
        assertEquals("Done.", runAgent(new CopyOnWriteArrayList<>()));
        String second = requests.get(1).replaceAll("\\s+", "");
        int thinkingAt = second.indexOf("\"type\":\"thinking\"");
        int toolUseAt = second.indexOf("\"type\":\"tool_use\"");
        assertTrue(thinkingAt >= 0, "thinking block replayed");
        assertTrue(second.contains("\"signature\":\"sig-t1\""), "signature preserved");
        assertTrue(thinkingAt < toolUseAt, "thinking precedes tool_use in the assistant turn");
    }

    @Test
    @DisplayName("A model repeating the same tool call is short-circuited and the task pauses with a summary at the step limit")
    void loopGuardAndStepLimit() throws Exception {
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.setMaxAgentIterations(5);
        ConfigManager.getInstance().saveConfig(cfg);
        Deque<String> responses = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < 5; i++) responses.add(toolUse("t" + i, null));
        responses.add(message("[{\"type\":\"text\",\"text\":\"Summary: read a.txt repeatedly.\"}]", "end_turn"));
        List<String> requests = startFakeAnthropic(responses);
        List<String> thoughts = new CopyOnWriteArrayList<>();

        String answer = runAgent(thoughts);

        assertEquals(6, requests.size(), "5 steps + 1 wrap-up request");
        assertTrue(requests.get(3).contains("it was not run again"), "third identical call is not executed again");
        assertTrue(requests.get(5).contains("You have used all 5 agent steps"));
        assertTrue(answer.startsWith("Summary: read a.txt repeatedly."));
        assertTrue(answer.contains("Paused after 5 agent steps"));
        assertTrue(thoughts.stream().anyMatch(t -> t.contains("Skipped a repeated call")));
    }

    @Test
    @DisplayName("Max agent steps setting is clamped and defaults to 50")
    void stepSetting() {
        IdeConfig c = new IdeConfig();
        assertEquals(50, c.getMaxAgentIterations());
        c.setMaxAgentIterations(0);
        assertEquals(50, c.getMaxAgentIterations(), "0 from older configs means default");
        c.setMaxAgentIterations(10_000);
        assertEquals(IdeConfig.MAX_AGENT_ITERATIONS, c.getMaxAgentIterations());
    }
}

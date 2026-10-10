package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.tools.AgentTool;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
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
 * Streaming replies: the agent chat receives answer text and reasoning as the provider streams them (OpenAI-compatible
 * and Anthropic SSE), while tool calls, reasoning replay, usage and the final answer stay exactly as without streaming.
 */
class StreamingAgentTest {

    private static final String PROVIDER = "TEST_STREAMING";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final boolean streamingBefore = ConfigManager.getInstance().getConfig().isStreamingEnabled();

    /** status, content type and body of a canned reply */
    record Reply(int status, String contentType, String body) {
        static Reply sse(String... events) {
            StringBuilder sb = new StringBuilder();
            for (String e : events) sb.append(e.startsWith("event:") || e.startsWith(":") ? e : "data: " + e).append("\n\n");
            return new Reply(200, "text/event-stream", sb.toString());
        }

        static Reply json(String body) {
            return new Reply(200, "application/json", body);
        }
    }

    /** Everything the agent reported, in order. */
    static final class Recorder implements AgentListener {
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final List<String> reasoning = new CopyOnWriteArrayList<>();
        final List<String> thoughts = new CopyOnWriteArrayList<>();
        final List<String> tools = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        public void onToken(String token) { tokens.add(token); }
        public void onReasoningToken(String token) { reasoning.add(token); }
        public void onThinking(String thought) { thoughts.add(thought); }
        public void onToolCall(String toolName, String input) { tools.add(toolName + " " + input); }
        public void onToolResult(String toolName, String output) { }
        public void onComplete(String fullResponse) { result.set(fullResponse); done.countDown(); }
        public void onError(Throwable t) { error.set(t); done.countDown(); }

        String text() { return String.join("", tokens); }
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        cfg.setStreamingEnabled(streamingBefore);
        ConfigManager.getInstance().saveConfig(cfg);
    }

    private List<String> startServer(String type, String model, Deque<Reply> replies) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply r = replies.isEmpty() ? new Reply(500, "text/plain", "no more replies") : replies.poll();
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", r.contentType());
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + ("ANTHROPIC".equals(type) ? "/v1/" : "/v1");
        ProviderConfig p = new ProviderConfig(PROVIDER, type, "Fake streaming", base, model,
                new ArrayList<>(List.of(new ModelDefinition(model, model, 128_000, 8_000, List.of()))));
        p.setApiKey("sk-test");
        p.setEnabled(true);
        cfg.getProviders().put(PROVIDER, p);
        cfg.setStreamingEnabled(true);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    private Recorder run(LangChainAgentService service, String model) throws Exception {
        SessionManager.getInstance().createSession("Streaming", PROVIDER, model, false);
        Recorder rec = new Recorder();
        service.sendMessage("Do the task", "", "", rec);
        assertTrue(rec.done.await(60, TimeUnit.SECONDS), "agent finished");
        if (rec.error.get() != null) fail(rec.error.get());
        return rec;
    }

    // ---------------------------------------------------------------- OpenAI-compatible chunks

    static String chunk(String deltaJson, String finish) {
        return "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":" + deltaJson
                + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}";
    }

    static String usageChunk(int in, int out) {
        return "{\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":" + in
                + ",\"completion_tokens\":" + out + ",\"total_tokens\":" + (in + out) + "}}";
    }

    static AgentTool echoTool() {
        return new AgentTool() {
            public String getName() { return "echo"; }
            public String getDescription() { return "echo: returns its input"; }
            public String execute(String arguments) { return "echoed " + arguments; }
        };
    }

    @Test
    @DisplayName("OpenAI-compatible: answer text arrives piece by piece and the pieces add up to the final answer")
    void openAiStreamsText() throws Exception {
        List<String> requests = startServer("CUSTOM", "gpt-4o", new ConcurrentLinkedDeque<>(List.of(Reply.sse(
                chunk("{\"role\":\"assistant\",\"content\":\"\"}", null),
                chunk("{\"content\":\"Hel\"}", null),
                ": keep-alive comment",
                chunk("{\"content\":\"lo, \"}", null),
                chunk("{\"content\":\"world\"}", "stop"),
                usageChunk(42, 7),
                "[DONE]"))));
        Recorder rec = run(new LangChainAgentService(), "gpt-4o");

        assertEquals(List.of("Hel", "lo, ", "world"), rec.tokens, "each delta is forwarded as it arrives, nothing repeated");
        assertEquals("Hello, world", rec.result.get());
        JsonNode body = MAPPER.readTree(requests.get(0));
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
        AgentSession s = SessionManager.getInstance().getActiveSession();
        assertTrue(s.getTokenTracker().getPromptTokens() >= 42, "usage from the last chunk is recorded");

        // The assembled reply carries the usage of the final chunk
        OpenAiCompatibleChatModel.StreamAssembler asm = new OpenAiCompatibleChatModel.StreamAssembler(d -> { });
        asm.accept(chunk("{\"content\":\"x\"}", "stop"));
        asm.accept(usageChunk(42, 7));
        var resp = OpenAiCompatibleChatModel.parseResponse(asm.toResponseJson());
        assertEquals(42, resp.tokenUsage().inputTokenCount());
        assertEquals(7, resp.tokenUsage().outputTokenCount());
        assertEquals(dev.langchain4j.model.output.FinishReason.STOP, resp.finishReason());
    }

    @Test
    @DisplayName("OpenAI-compatible: tool calls split across chunks are reassembled, reasoning streams and is replayed")
    void openAiStreamsToolCallsAndReasoning() throws Exception {
        List<String> requests = startServer("CUSTOM", "deepseek-reasoner", new ConcurrentLinkedDeque<>(List.of(
                Reply.sse(
                        chunk("{\"role\":\"assistant\",\"reasoning_content\":\"I should \"}", null),
                        chunk("{\"reasoning_content\":\"echo first.\"}", null),
                        chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"\"}}]}", null),
                        chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"input\\\":\"}}]}", null),
                        chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"ping\\\"}\"}}]}", "tool_calls"),
                        "[DONE]"),
                Reply.sse(chunk("{\"content\":\"Done: ping\"}", "stop"), "[DONE]"))));
        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(echoTool());
        Recorder rec = run(service, "deepseek-reasoner");

        assertEquals("Done: ping", rec.result.get());
        assertEquals(List.of("echo ping"), rec.tools, "the assembled arguments reach the tool");
        assertEquals("I should echo first.", String.join("", rec.reasoning));
        assertTrue(rec.thoughts.stream().noneMatch(t -> t.startsWith("💭 Model Reasoning")),
                "streamed reasoning is not repeated as a separate reasoning message");
        AgentSession s = SessionManager.getInstance().getActiveSession();
        assertTrue(s.getMessages().stream().anyMatch(m -> m.getRole() == AgentRole.THINKING
                && m.getContent().equals("I should echo first.")), "reasoning is still saved in the session");

        JsonNode msgs = MAPPER.readTree(requests.get(1)).path("messages");
        JsonNode assistant = null;
        for (JsonNode m : msgs) if ("assistant".equals(m.path("role").asText())) assistant = m;
        assertNotNull(assistant);
        assertEquals("I should echo first.", assistant.path("reasoning_content").asText(), "reasoning replayed with the tool turn");
        assertEquals("call_9", assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals("{\"input\":\"ping\"}", assistant.path("tool_calls").get(0).path("function").path("arguments").asText());
    }

    @Test
    @DisplayName("Inline <think> tags split across chunks go to reasoning; only the answer reaches the chat")
    void thinkTagsAcrossChunks() throws Exception {
        startServer("CUSTOM", "qwq", new ConcurrentLinkedDeque<>(List.of(Reply.sse(
                chunk("{\"content\":\"<thi\"}", null),
                chunk("{\"content\":\"nk>Plan the \"}", null),
                chunk("{\"content\":\"answer.</th\"}", null),
                chunk("{\"content\":\"ink>\\n\\nThe answer\"}", null),
                chunk("{\"content\":\" is 4.\"}", "stop"),
                "[DONE]"))));
        Recorder rec = run(new LangChainAgentService(), "qwq");

        assertEquals("The answer is 4.", rec.text());
        assertEquals("The answer is 4.", rec.result.get());
        assertEquals("Plan the answer.", String.join("", rec.reasoning));
        assertTrue(rec.tokens.stream().noneMatch(t -> t.contains("<") || t.contains("think")), rec.tokens.toString());
    }

    @Test
    @DisplayName("A cut-off answer streams as usual and only the truncation note is sent afterwards")
    void truncationNoteAppended() throws Exception {
        startServer("CUSTOM", "gpt-4o", new ConcurrentLinkedDeque<>(List.of(Reply.sse(
                chunk("{\"content\":\"Partial answer\"}", "length"), "[DONE]"))));
        Recorder rec = run(new LangChainAgentService(), "gpt-4o");

        assertEquals("Partial answer", rec.tokens.get(0));
        assertTrue(rec.tokens.get(1).contains("Response truncated"), rec.tokens.toString());
        assertEquals(rec.result.get(), rec.text(), "the chat shows exactly the final answer");
    }

    @Test
    @DisplayName("Servers that ignore \"stream\" or reject stream_options still work")
    void fallbacks() throws Exception {
        String whole = "{\"id\":\"c\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Whole reply\"},\"finish_reason\":\"stop\"}]}";
        List<String> requests = startServer("CUSTOM", "local-model", new ConcurrentLinkedDeque<>(List.of(
                new Reply(400, "application/json", "{\"error\":{\"message\":\"Unrecognized request argument: stream_options\"}}"),
                Reply.json(whole))));
        Recorder rec = run(new LangChainAgentService(), "local-model");

        assertEquals(List.of("Whole reply"), rec.tokens);
        assertEquals("Whole reply", rec.result.get());
        assertEquals(2, requests.size());
        assertTrue(MAPPER.readTree(requests.get(0)).has("stream_options"));
        assertFalse(MAPPER.readTree(requests.get(1)).has("stream_options"), "retried without stream_options");
    }

    @Test
    @DisplayName("With streaming turned off the answer arrives in one piece and the request does not ask to stream")
    void streamingDisabled() throws Exception {
        String whole = "{\"id\":\"c\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"One piece\"},\"finish_reason\":\"stop\"}]}";
        List<String> requests = startServer("CUSTOM", "gpt-4o", new ConcurrentLinkedDeque<>(List.of(Reply.json(whole))));
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.setStreamingEnabled(false);
        ConfigManager.getInstance().saveConfig(cfg);
        Recorder rec = run(new LangChainAgentService(), "gpt-4o");

        assertEquals(List.of("One piece"), rec.tokens);
        assertFalse(MAPPER.readTree(requests.get(0)).has("stream"));
    }

    @Test
    @DisplayName("Text before a tool call is saved as an interim reply, shown without streaming, and not resent later")
    void preToolTextSaved() throws Exception {
        String toolTurn = "{\"id\":\"c\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Let me echo that.\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"echo\","
                + "\"arguments\":\"{\\\"input\\\":\\\"hi\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        String answer = "{\"id\":\"c\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"%s\"},\"finish_reason\":\"stop\"}]}";
        List<String> requests = startServer("CUSTOM", "gpt-4o", new ConcurrentLinkedDeque<>(List.of(
                Reply.json(toolTurn), Reply.json(answer.formatted("It echoed hi.")), Reply.json(answer.formatted("Sure.")))));
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.setStreamingEnabled(false);
        ConfigManager.getInstance().saveConfig(cfg);
        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(echoTool());
        Recorder rec = run(service, "gpt-4o");

        assertEquals(List.of("Let me echo that.", "It echoed hi."), rec.tokens, "the pre-tool text reaches the chat without streaming too");
        AgentSession s = SessionManager.getInstance().getActiveSession();
        List<String> order = new ArrayList<>();
        for (AgentMessage m : s.getMessages()) {
            if (m.getRole() == AgentRole.ASSISTANT) order.add((m.isInterim() ? "interim:" : "answer:") + m.getContent());
            else if (m.getRole() == AgentRole.TOOL_CALL) order.add("tool:" + m.getToolName());
        }
        assertEquals(List.of("interim:Let me echo that.", "tool:echo", "answer:It echoed hi."), order);

        AgentMessage saved = s.getMessages().stream().filter(AgentMessage::isInterim).findFirst().orElseThrow();
        AgentMessage back = MAPPER.readValue(MAPPER.writeValueAsString(saved), AgentMessage.class);
        assertTrue(back.isInterim(), "survives saving the session");
        assertFalse(MAPPER.writeValueAsString(s.getMessages().get(s.getMessages().size() - 1)).contains("interim"),
                "ordinary messages are saved as before");

        // Follow-up turn: earlier answers are resent, the pre-tool text is not
        Recorder next = new Recorder();
        service.sendMessage("Thanks", "", "", next);
        assertTrue(next.done.await(60, TimeUnit.SECONDS));
        String followUp = requests.get(2);
        assertTrue(followUp.contains("It echoed hi."));
        assertFalse(followUp.contains("Let me echo that."), followUp);
    }

    // ---------------------------------------------------------------- Anthropic events

    static String ev(String json) {
        return json;
    }

    @Test
    @DisplayName("Anthropic: thinking and text stream; tool input arrives in pieces; signed thinking is replayed")
    void anthropicStreams() throws Exception {
        Reply toolTurn = Reply.sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":30,\"output_tokens\":1}}}",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Need to \"}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"echo.\"}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-abc\"}}"),
                ev("{\"type\":\"content_block_stop\",\"index\":0}"),
                ev("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me check.\"}}"),
                ev("{\"type\":\"content_block_stop\",\"index\":1}"),
                ev("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"echo\",\"input\":{}}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"inp\"}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ut\\\": \\\"pong\\\"}\"}}"),
                ev("{\"type\":\"content_block_stop\",\"index\":2}"),
                ev("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":25}}"),
                ev("{\"type\":\"message_stop\"}"));
        Reply answer = Reply.sse(
                ev("{\"type\":\"message_start\",\"message\":{\"id\":\"m2\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":60,\"output_tokens\":1}}}"),
                ev("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"It said \"}}"),
                ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"pong.\"}}"),
                ev("{\"type\":\"content_block_stop\",\"index\":0}"),
                ev("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}"),
                ev("{\"type\":\"message_stop\"}"));
        List<String> requests = startServer("ANTHROPIC", "claude-sonnet-4-5", new ConcurrentLinkedDeque<>(List.of(toolTurn, answer)));
        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(echoTool());
        Recorder rec = run(service, "claude-sonnet-4-5");

        assertEquals("It said pong.", rec.result.get());
        assertEquals(List.of("Let me check.", "It said ", "pong."), rec.tokens, "narration before the tool call streams too");
        assertEquals("Need to echo.", String.join("", rec.reasoning));
        assertEquals(List.of("echo pong"), rec.tools);
        assertTrue(SessionManager.getInstance().getActiveSession().getMessages().stream()
                .anyMatch(m -> m.isInterim() && m.getContent().equals("Let me check.")), "streamed pre-tool text is saved");

        JsonNode first = MAPPER.readTree(requests.get(0));
        assertTrue(first.path("stream").asBoolean());
        assertEquals(8000, first.path("max_tokens").asInt(), "the model's Max Output is used");

        JsonNode msgs = MAPPER.readTree(requests.get(1)).path("messages");
        JsonNode assistant = msgs.get(msgs.size() - 2);
        assertEquals("assistant", assistant.path("role").asText());
        JsonNode thinking = assistant.path("content").get(0);
        assertEquals("thinking", thinking.path("type").asText(), "thinking comes first in the replayed turn");
        assertEquals("Need to echo.", thinking.path("thinking").asText());
        assertEquals("sig-abc", thinking.path("signature").asText());
        JsonNode toolUse = null;
        for (JsonNode c : assistant.path("content")) if ("tool_use".equals(c.path("type").asText())) toolUse = c;
        assertNotNull(toolUse);
        assertEquals("pong", toolUse.path("input").path("input").asText());

        // Input tokens come from message_start, output tokens from the last message_delta
        AnthropicSseChatModel.Assembler asm = new AnthropicSseChatModel.Assembler(d -> { });
        asm.accept("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":30,\"output_tokens\":1}}}");
        asm.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":25}}");
        var resp = asm.toResponse();
        assertEquals(30, resp.tokenUsage().inputTokenCount());
        assertEquals(25, resp.tokenUsage().outputTokenCount());
        assertEquals(dev.langchain4j.model.output.FinishReason.LENGTH, resp.finishReason());
    }

    @Test
    @DisplayName("Anthropic: a stream error event fails the task with the server's message")
    void anthropicStreamError() throws Exception {
        startServer("ANTHROPIC", "claude-sonnet-4-5", new ConcurrentLinkedDeque<>(List.of(Reply.sse(
                ev("{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}"),
                ev("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")))));
        SessionManager.getInstance().createSession("Streaming", PROVIDER, "claude-sonnet-4-5", false);
        Recorder rec = new Recorder();
        new LangChainAgentService().sendMessage("Do the task", "", "", rec);
        assertTrue(rec.done.await(60, TimeUnit.SECONDS));
        String reported = rec.error.get() != null ? String.valueOf(rec.error.get().getMessage()) : rec.result.get();
        assertTrue(reported != null && reported.contains("Overloaded"), String.valueOf(reported));
        assertTrue(LangChainAgentService.isProviderFailure(rec.error.get()), "a provider failure, logged without a stack trace");
        assertFalse(LangChainAgentService.isProviderFailure(new NullPointerException()), "IDE bugs keep their stack trace");
    }

    // ---------------------------------------------------------------- SSE reader

    @Test
    @DisplayName("SSE reader joins multi-line data, skips comments and other fields, stops at [DONE] and on cancel")
    void sseReader() throws Exception {
        String stream = ": hello\nevent: x\ndata: {\"a\":\ndata: 1}\nid: 7\n\ndata: second\n\ndata: [DONE]\n\ndata: after\n\n";
        List<String> got = new ArrayList<>();
        assertTrue(SseReader.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), () -> false, got::add));
        assertEquals(List.of("{\"a\":\n1}", "second"), got);

        List<String> none = new ArrayList<>();
        assertFalse(SseReader.read(new ByteArrayInputStream("data: x\n\n".getBytes(StandardCharsets.UTF_8)), () -> true, none::add));
        assertTrue(none.isEmpty());

        assertEquals("abc", LangChainAgentService.unstreamedPart("abc", ""));
        assertEquals("\n\nnote", LangChainAgentService.unstreamedPart("abc\n\nnote", "abc"));
        assertEquals("", LangChainAgentService.unstreamedPart("different", "abc"));
    }
}

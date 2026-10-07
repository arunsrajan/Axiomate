package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.*;

/** The chat transcript built from a streamed reply: one bubble per reply, a live reasoning bubble, split at tool calls. */
class StreamingUiTest {

    private static final String PROVIDER = "TEST_STREAMING_UI";
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        ConfigManager.getInstance().saveConfig(cfg);
    }

    static String chunk(String delta, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }

    @Test
    @DisplayName("Streamed reasoning, narration, tool call and answer appear as separate transcript entries")
    void transcriptFromStream() throws Exception {
        Deque<String> replies = new ConcurrentLinkedDeque<>(List.of(
                chunk("{\"reasoning_content\":\"I should \"}", null) + chunk("{\"reasoning_content\":\"look.\"}", null)
                        + chunk("{\"content\":\"Check\"}", null) + chunk("{\"content\":\"ing.\"}", null)
                        + chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}", "tool_calls")
                        + "data: [DONE]\n\n",
                chunk("{\"content\":\"Final \"}", null) + chunk("{\"content\":\"answer.\"}", "stop") + "data: [DONE]\n\n"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = (replies.isEmpty() ? "data: [DONE]\n\n" : replies.poll()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        ProviderConfig p = new ProviderConfig(PROVIDER, "CUSTOM", "Fake", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "deepseek-reasoner", new ArrayList<>(List.of(new ModelDefinition("deepseek-reasoner", "R", 128_000, 8_000, List.of()))));
        p.setApiKey("k");
        p.setEnabled(true);
        cfg.getProviders().put(PROVIDER, p);
        cfg.setStreamingEnabled(true);
        ConfigManager.getInstance().saveConfig(cfg);
        SessionManager.getInstance().createSession("UI streaming", PROVIDER, "deepseek-reasoner", false);

        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        AIAgentPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> panel.sendPromptDirectly("go"));
        long deadline = System.currentTimeMillis() + 30_000;
        Thread.sleep(200);
        while (AgentManager.getInstance().getActiveService().isBusy() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });

        List<String> entries = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : all(panel, AIAgentPanel.MessageCard.class)) {
                AIAgentPanel.MessageCard card = (AIAgentPanel.MessageCard) c;
                String text = text(card).strip();
                switch (card.getDisplayType()) {
                    case WALKTHROUGH -> {
                        if (!text.isEmpty() && !text.startsWith("/help")) entries.add("ANSWER " + text);
                    }
                    case THINKING -> {
                        if (text.contains("look.")) entries.add("THINKING " + text);
                    }
                    case TOOL_REQUEST -> entries.add("TOOL");
                    default -> {
                    }
                }
            }
        });
        assertEquals(List.of("THINKING I should look.", "ANSWER Checking.", "TOOL", "ANSWER Final answer."), entries);
    }

    private static List<Component> all(Container c, Class<?> type) {
        List<Component> out = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) out.add(k);
            if (k instanceof Container cc) out.addAll(all(cc, type));
        }
        return out;
    }

    private static String text(Container c) {
        StringBuilder sb = new StringBuilder();
        for (Component k : c.getComponents()) {
            if (k instanceof JTextArea a) sb.append(a.getText());
            else if (k instanceof Container cc) sb.append(text(cc));
        }
        return sb.toString();
    }
}

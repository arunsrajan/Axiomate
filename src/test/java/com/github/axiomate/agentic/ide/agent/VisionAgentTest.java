package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.tools.ViewImageTool;
import com.github.axiomate.agentic.ide.agent.vision.ImageAttachment;
import com.github.axiomate.agentic.ide.agent.vision.VisionSupport;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * Images reach vision models on the OpenAI-compatible and Anthropic paths, text-only models get a note
 * instead, the view_image tool delivers images to the model, and later turns resend earlier images.
 */
class VisionAgentTest {

    private static final String PROVIDER = "TEST_VISION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private File previousProject;

    @TempDir
    Path project;

    @BeforeEach
    void setUp() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(project.toFile());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        ConfigManager.getInstance().saveConfig(cfg);
        File back = previousProject != null ? previousProject : new File(System.getProperty("user.dir"));
        ProjectManager.getInstance().setCurrentProjectDirectory(back);
        SessionManager.getInstance().loadSessionsForProject(back);
    }

    private File png(String name) throws Exception {
        File f = project.resolve(name).toFile();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", f);
        return f;
    }

    static String openAiOk(String messageJson, String finish) {
        return "{\"id\":\"c\",\"object\":\"chat.completion\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":" + messageJson
                + ",\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    static String openAiText(String text) {
        return openAiOk("{\"role\":\"assistant\",\"content\":\"" + text + "\"}", "stop");
    }

    private List<String> startServer(String type, String model, Deque<String> replies) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = replies.isEmpty() ? openAiText("(no more)") : replies.poll();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + ("ANTHROPIC".equals(type) ? "/v1/" : "/v1");
        ProviderConfig p = new ProviderConfig(PROVIDER, type, "Fake vision", base, model,
                new ArrayList<>(List.of(new ModelDefinition(model, model, 128_000, 4_096, List.of()))));
        p.setApiKey("sk-test");
        p.setEnabled(true);
        cfg.getProviders().put(PROVIDER, p);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    private String run(LangChainAgentService service, String prompt, List<ImageAttachment> images, List<String> thoughts)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        service.sendMessage(prompt, "", "", images, new AgentListener() {
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

    /** Image parts of every user message in an OpenAI-style request. */
    private static List<String> imageUrls(String request) throws Exception {
        List<String> urls = new ArrayList<>();
        for (JsonNode m : MAPPER.readTree(request).path("messages")) {
            if (!"user".equals(m.path("role").asText())) continue;
            for (JsonNode part : m.path("content")) {
                if ("image_url".equals(part.path("type").asText())) urls.add(part.path("image_url").path("url").asText());
            }
        }
        return urls;
    }

    @Test
    @DisplayName("OpenAI-compatible vision model receives attached images as image_url parts; later turns resend them")
    void openAiVisionModelGetsImages() throws Exception {
        List<String> requests = startServer("CUSTOM", "gpt-4o",
                new ConcurrentLinkedDeque<>(List.of(openAiText("A login form."), openAiText("The button is blue."))));
        AgentSession session = SessionManager.getInstance().createSession("Vision", PROVIDER, "gpt-4o", false);
        ImageAttachment shot = VisionSupport.fromFile(png("login.png"));
        LangChainAgentService service = new LangChainAgentService();

        assertEquals("A login form.", run(service, "What is in this screenshot?", List.of(shot), new ArrayList<>()));
        List<String> urls = imageUrls(requests.get(0));
        assertEquals(List.of("data:image/png;base64," + shot.base64Data()), urls);
        JsonNode last = MAPPER.readTree(requests.get(0)).path("messages");
        last = last.get(last.size() - 1);
        assertEquals("text", last.path("content").get(0).path("type").asText(), "text comes before the image");
        assertTrue(last.path("content").get(0).path("text").asText().contains("Attached image(s): login.png (40×30)"));

        AgentMessage userRecord = session.getMessages().stream().filter(AgentMessage::isUser).findFirst().orElseThrow();
        assertEquals(List.of(shot.path()), userRecord.getAttachments(), "the session remembers the image file");

        run(service, "What colour is the button?", List.of(), new ArrayList<>());
        assertEquals(1, imageUrls(requests.get(1)).size(), "the earlier image is resent with the follow-up");
    }

    @Test
    @DisplayName("Text-only model: images are not sent, the prompt says so and the user is told")
    void textOnlyModelSkipsImages() throws Exception {
        List<String> requests = startServer("CUSTOM", "deepseek-chat",
                new ConcurrentLinkedDeque<>(List.of(openAiText("I cannot see images."))));
        SessionManager.getInstance().createSession("Text", PROVIDER, "deepseek-chat", false);
        List<String> thoughts = new ArrayList<>();
        run(new LangChainAgentService(), "Describe", List.of(VisionSupport.fromFile(png("a.png"))), thoughts);

        assertTrue(imageUrls(requests.get(0)).isEmpty());
        assertTrue(requests.get(0).contains("cannot view images: a.png"), requests.get(0));
        assertTrue(thoughts.stream().anyMatch(t -> t.contains("deepseek-chat does not accept images")), thoughts.toString());
    }

    @Test
    @DisplayName("view_image: the image the agent asked for follows the tool result as a user turn")
    void viewImageToolDeliversImage() throws Exception {
        png("mockup.png");
        String toolCall = openAiOk("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"view_image\",\"arguments\":\"{\\\"input\\\":\\\"{\\\\\\\"path\\\\\\\":\\\\\\\"mockup.png\\\\\\\"}\\\"}\"}}]}",
                "tool_calls");
        List<String> requests = startServer("CUSTOM", "gpt-4o",
                new ConcurrentLinkedDeque<>(List.of(toolCall, openAiText("The mockup shows a dashboard."))));
        SessionManager.getInstance().createSession("Tool", PROVIDER, "gpt-4o", false);
        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(new ViewImageTool());

        assertEquals("The mockup shows a dashboard.", run(service, "Implement mockup.png", List.of(), new ArrayList<>()));
        assertEquals(2, requests.size());
        JsonNode msgs = MAPPER.readTree(requests.get(1)).path("messages");
        JsonNode tool = msgs.get(msgs.size() - 2);
        JsonNode user = msgs.get(msgs.size() - 1);
        assertEquals("tool", tool.path("role").asText());
        assertTrue(tool.path("content").asText().startsWith("Loaded mockup.png (40×30)"), tool.toString());
        assertEquals("user", user.path("role").asText());
        assertEquals("image_url", user.path("content").get(1).path("type").asText());
    }

    @Test
    @DisplayName("Anthropic path sends images as base64 image blocks")
    void anthropicGetsImageBlocks() throws Exception {
        String reply = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"A chart.\"}],\"stop_reason\":\"end_turn\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}";
        List<String> requests = startServer("ANTHROPIC", "claude-sonnet-4-5", new ConcurrentLinkedDeque<>(List.of(reply)));
        SessionManager.getInstance().createSession("Claude", PROVIDER, "claude-sonnet-4-5", false);
        ImageAttachment chart = VisionSupport.fromFile(png("chart.png"));

        assertEquals("A chart.", run(new LangChainAgentService(), "Explain", List.of(chart), new ArrayList<>()));
        JsonNode msgs = MAPPER.readTree(requests.get(0)).path("messages");
        JsonNode content = msgs.get(msgs.size() - 1).path("content");
        JsonNode image = null;
        for (JsonNode c : content) if ("image".equals(c.path("type").asText())) image = c;
        assertNotNull(image, content.toString());
        assertEquals("base64", image.path("source").path("type").asText());
        assertEquals("image/png", image.path("source").path("media_type").asText());
        assertEquals(chart.base64Data(), image.path("source").path("data").asText());
    }
}

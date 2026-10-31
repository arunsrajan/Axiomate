package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.tools.FileSystemTool;
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

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Plan, Manual and Auto execution modes: which tool calls run, who is asked, and what the agent is told. */
class ExecutionModesTest {

    private static final String PROVIDER = "TEST_EXECUTION_MODES";
    @TempDir
    Path project;
    private HttpServer server;
    private File previousProject;

    @BeforeEach
    void setUp() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(project.toFile());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        ToolPermissions.setApprovalHandler(null);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        cfg.setManualModeAsksForReadOnlyTools(false);
        ConfigManager.getInstance().saveConfig(cfg);
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    private static String write(String path) {
        return "{\"action\":\"write\",\"path\":\"" + path + "\",\"content\":\"hi\"}";
    }

    @Test
    @DisplayName("Read-only calls are recognised per action; shells, edits and MCP tools are not read-only")
    void readOnlyClassification() {
        assertTrue(ToolPermissions.isReadOnly("file_system", "{\"action\":\"read\",\"path\":\"a\"}"));
        assertTrue(ToolPermissions.isReadOnly("file_system", "{\"action\":\"list\"}"));
        assertFalse(ToolPermissions.isReadOnly("file_system", write("a")));
        assertTrue(ToolPermissions.isReadOnly("code_editor", "{\"action\":\"read_file\",\"filePath\":\"a\"}"));
        assertFalse(ToolPermissions.isReadOnly("code_editor", "{\"action\":\"replace_content\"}"));
        assertFalse(ToolPermissions.isReadOnly("code_editor", "{}"), "code_editor defaults to replace_content");
        assertTrue(ToolPermissions.isReadOnly("agent_memory", "{\"action\":\"search\",\"query\":\"x\"}"));
        assertFalse(ToolPermissions.isReadOnly("agent_memory", "{\"action\":\"remember\"}"));
        for (String t : List.of("search_files", "view_image", "task_list")) assertTrue(ToolPermissions.isReadOnly(t, "{}"), t);
        for (String t : List.of("bash", "powershell", "terminal", "code_refactor", "mcp_github_create_issue")) {
            assertFalse(ToolPermissions.isReadOnly(t, "{\"command\":\"ls\"}"), t);
        }
        assertEquals(ExecutionMode.PLAN, ExecutionMode.parse("plan"));
        assertEquals(ExecutionMode.MANUAL, ExecutionMode.parse(" Manual "));
        assertNull(ExecutionMode.parse("yolo"));
        assertEquals(ExecutionMode.PLAN, ExecutionMode.AUTO.next(), "Shift+Tab cycles back to Plan");
    }

    @Test
    @DisplayName("Plan refuses changes, Auto allows them, Manual asks and remembers \"always allow\" for the session")
    void permissionRules() {
        AgentSession s = new AgentSession("Rules", "OPENAI", "gpt-4o", 128_000);
        assertTrue(ToolPermissions.check(s, "bash", "{\"command\":\"rm x\"}").allowed(), "Auto runs everything");

        s.setExecutionMode(ExecutionMode.PLAN);
        ToolPermissions.Verdict plan = ToolPermissions.check(s, "bash", "{\"command\":\"mvn test\"}");
        assertFalse(plan.allowed());
        assertTrue(plan.refusal().startsWith("BLOCKED: Plan mode"), plan.refusal());
        assertTrue(ToolPermissions.check(s, "search_files", "{\"query\":\"x\"}").allowed());

        s.setExecutionMode(ExecutionMode.MANUAL);
        assertFalse(ToolPermissions.check(s, "bash", "{}").allowed(), "nobody to ask: refused, not run");

        List<ToolPermissions.Request> asked = new CopyOnWriteArrayList<>();
        ToolPermissions.setApprovalHandler(r -> {
            asked.add(r);
            return r.toolName().equals("bash") ? ToolPermissions.Answer.deny("not on main") : ToolPermissions.Answer.allowAlways();
        });
        ToolPermissions.Verdict denied = ToolPermissions.check(s, "bash", "{\"command\":\"git push\"}");
        assertFalse(denied.allowed());
        assertTrue(denied.refusal().contains("Reason: not on main."), denied.refusal());
        assertEquals("Rules", asked.get(0).sessionName());

        assertTrue(ToolPermissions.wouldAsk(s, "code_editor", "{\"action\":\"write_file\"}"));
        assertTrue(ToolPermissions.check(s, "code_editor", "{\"action\":\"write_file\"}").allowed());
        assertEquals(List.of("code_editor"), s.getAllowedTools());
        assertFalse(ToolPermissions.wouldAsk(s, "code_editor", "{\"action\":\"write_file\"}"));
        assertTrue(ToolPermissions.check(s, "code_editor", "{\"action\":\"delete_lines\"}").allowed());
        assertEquals(2, asked.size(), "always-allowed tools are not asked about again");

        assertTrue(ToolPermissions.check(s, "file_system", "{\"action\":\"read\"}").allowed());
        assertEquals(2, asked.size(), "read-only calls run without asking by default");
        ConfigManager.getInstance().getConfig().setManualModeAsksForReadOnlyTools(true);
        ToolPermissions.check(s, "file_system", "{\"action\":\"read\"}");
        assertEquals(3, asked.size(), "unless the user wants to be asked about those too");
    }

    // ---------------------------------------------------------------- through the agent loop

    private List<String> startServer(java.util.function.IntFunction<String> replies) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                AgentLoopFixesTest.json(ex, replies.apply(count.getAndIncrement()));
            } catch (Exception ignored) {
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
        cfg.setStreamingEnabled(false);
        ConfigManager.getInstance().saveConfig(cfg);
        return requests;
    }

    private AgentSession run(ExecutionMode mode, List<String> requests) throws Exception {
        AgentSession session = SessionManager.getInstance().createSession("Modes", PROVIDER, "gpt-4o", false);
        session.setExecutionMode(mode);
        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(new FileSystemTool());
        AgentLoopFixesTest.Recorder rec = new AgentLoopFixesTest.Recorder();
        service.sendMessage("Add the files", "", "", rec);
        assertTrue(rec.done.await(60, TimeUnit.SECONDS));
        assertFalse(rec.events.stream().anyMatch(e -> e.startsWith("error:")), rec.events.toString());
        return session;
    }

    @Test
    @DisplayName("Plan mode: the agent is told it is planning, and a write it tries is refused, not run")
    void planModeInTheLoop() throws Exception {
        List<String> requests = startServer(n -> n == 0 ? AgentLoopFixesTest.toolCall("file_system", write("plan.txt"), 10)
                : AgentLoopFixesTest.text("1. Create plan.txt\\n2. Verify it", 10));
        run(ExecutionMode.PLAN, requests);

        assertFalse(Files.exists(project.resolve("plan.txt")), "nothing is written in Plan mode");
        assertTrue(requests.get(0).contains("PLAN MODE"), "the system prompt says so");
        assertTrue(requests.get(1).contains("BLOCKED: Plan mode"), "the model learns why the call did not run");
    }

    @Test
    @DisplayName("Manual mode: a denied write is not run and the reason reaches the model; \"always allow\" asks once")
    void manualModeInTheLoop() throws Exception {
        List<ToolPermissions.Request> asked = new CopyOnWriteArrayList<>();
        ToolPermissions.setApprovalHandler(r -> {
            asked.add(r);
            return ToolPermissions.Answer.deny("wrong folder");
        });
        List<String> requests = startServer(n -> n == 0 ? AgentLoopFixesTest.toolCall("file_system", write("denied.txt"), 10)
                : AgentLoopFixesTest.text("ok", 10));
        run(ExecutionMode.MANUAL, requests);
        assertFalse(Files.exists(project.resolve("denied.txt")));
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).arguments().contains("denied.txt"), "the user sees exactly what will run");
        assertTrue(requests.get(1).contains("DENIED") && requests.get(1).contains("wrong folder"), requests.get(1));

        server.stop(0);
        asked.clear();
        ToolPermissions.setApprovalHandler(r -> {
            asked.add(r);
            return ToolPermissions.Answer.allowAlways();
        });
        requests = startServer(n -> switch (n) {
            case 0 -> AgentLoopFixesTest.toolCall("file_system", write("a.txt"), 10);
            case 1 -> AgentLoopFixesTest.toolCall("file_system", write("b.txt"), 10);
            default -> AgentLoopFixesTest.text("done", 10);
        });
        AgentSession session = run(ExecutionMode.MANUAL, requests);
        assertTrue(Files.exists(project.resolve("a.txt")) && Files.exists(project.resolve("b.txt")));
        assertEquals(1, asked.size(), "asked once, then always allowed for this session");
        assertEquals(List.of("file_system"), session.getAllowedTools());
    }
}

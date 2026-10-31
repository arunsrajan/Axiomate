package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.agent.FileChangeTracker.FileChange;
import com.github.axiomate.agentic.ide.agent.FileChangeTracker.Kind;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.tools.AutonomousCodeEditorTool;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Lines added and removed per file, from the agent's tools and (in git repositories) from anything else. */
class FileChangesTest {

    private static final String PROVIDER = "TEST_FILE_CHANGES";
    @TempDir
    Path dir;
    private File previousProject;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
    }

    @AfterEach
    void tearDown() {
        FileChangeTracker.clear();
        if (server != null) server.stop(0);
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        cfg.getProviders().remove(PROVIDER);
        ConfigManager.getInstance().saveConfig(cfg);
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    @Test
    @DisplayName("Line counts: additions, removals, replacements, new and deleted files; line endings do not count")
    void lineDelta() {
        assertArrayEquals(new int[]{0, 0}, FileChangeTracker.lineDelta("a\nb\n", "a\nb\n"));
        assertArrayEquals(new int[]{2, 0}, FileChangeTracker.lineDelta("a\nb\n", "a\nx\ny\nb\n"));
        assertArrayEquals(new int[]{1, 1}, FileChangeTracker.lineDelta("a\nb\nc\n", "a\nB\nc\n"));
        assertArrayEquals(new int[]{0, 2}, FileChangeTracker.lineDelta("a\nb\nc\n", "c\n"));
        assertArrayEquals(new int[]{3, 0}, FileChangeTracker.lineDelta(null, "1\n2\n3"));
        assertArrayEquals(new int[]{0, 2}, FileChangeTracker.lineDelta("1\n2\n", null));
        assertArrayEquals(new int[]{0, 0}, FileChangeTracker.lineDelta("a\r\nb\r\n", "a\nb\n"));
        assertArrayEquals(new int[]{2, 2}, FileChangeTracker.lineDelta("a\nb\nc\nd\n", "b\na\nd\nc\n"), "moved lines");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20_000; i++) big.append("line ").append(i).append('\n');
        String edited = big.toString().replace("line 777\n", "changed 777\n").replace("line 15000\n", "");
        assertArrayEquals(new int[]{1, 2}, FileChangeTracker.lineDelta(big.toString(), edited), "small edit in a big file");

        StringBuilder other = new StringBuilder();
        for (int i = 0; i < 6_000; i++) other.append("new ").append(i).append('\n');
        StringBuilder old = new StringBuilder();
        for (int i = 0; i < 6_000; i++) old.append("old ").append(i).append('\n');
        assertArrayEquals(new int[]{6_000, 6_000}, FileChangeTracker.lineDelta(old.toString(), other.toString()),
                "a full rewrite beyond the exact diff's limit is still counted");
        assertEquals(" (+1 -1)", FileChangeTracker.deltaLabel("a\n", "b\n"));
        assertEquals("", FileChangeTracker.deltaLabel("a\n", "a\n"));
    }

    @Test
    @DisplayName("The summary text round-trips: counts, new and deleted files, paths with spaces, uncounted files")
    void describeAndParse() {
        List<FileChange> changes = List.of(
                new FileChange("docs/read me.md", 3, 1, Kind.MODIFIED),
                new FileChange("src/New.java", 12, 0, Kind.CREATED),
                new FileChange("old.txt", 0, 4, Kind.DELETED),
                new FileChange("logo.png", -1, -1, Kind.MODIFIED));
        String text = FileChangeTracker.describe(changes);
        assertEquals("Changed 4 files (+15 -5)\ndocs/read me.md +3 -1\nsrc/New.java +12 -0 (new)\nold.txt +0 -4 (deleted)\n"
                + "logo.png (not counted)", text);
        assertEquals(changes, FileChangeTracker.parse(text));
        assertTrue(FileChangeTracker.parse(null).isEmpty());
    }

    @Test
    @DisplayName("Tools: each edit reports its +/- lines; the run sums a file's edits from its content before the run")
    void toolsAreTracked() throws Exception {
        Path app = dir.resolve("App.java");
        Files.writeString(app, "class App {\n    int a;\n}\n");
        FileChangeTracker.begin(dir.toFile());
        AutonomousCodeEditorTool editor = new AutonomousCodeEditorTool();

        String r1 = editor.execute("{\"action\":\"replace_content\",\"filePath\":\"App.java\",\"target\":\"int a;\",\"replacement\":\"int a;\\n    int b;\"}");
        assertTrue(r1.startsWith("SUCCESS") && r1.endsWith(" (+1 -0)"), r1);
        String r2 = editor.execute("{\"action\":\"replace_lines\",\"filePath\":\"App.java\",\"startLine\":2,\"endLine\":2,\"replacement\":\"    long a;\"}");
        assertTrue(r2.endsWith(" (+1 -1)"), r2);
        String r3 = editor.execute("{\"action\":\"write_file\",\"filePath\":\"src/Util.java\",\"content\":\"class Util {\\n}\\n\"}");
        assertTrue(r3.endsWith(" (+2 -0)"), r3);
        assertFalse(editor.execute("{\"action\":\"read_file\",\"filePath\":\"App.java\"}").contains("(+"), "reads change nothing");
        String r4 = new FileSystemTool().execute("{\"action\":\"write\",\"path\":\"notes.txt\",\"content\":\"one\\ntwo\\n\"}");
        assertTrue(r4.endsWith(" (+2 -0)"), r4);

        List<FileChange> changes = FileChangeTracker.finish();
        assertEquals(List.of(
                new FileChange("App.java", 2, 1, Kind.MODIFIED),
                new FileChange("notes.txt", 2, 0, Kind.CREATED),
                new FileChange("src/Util.java", 2, 0, Kind.CREATED)), changes);
        assertFalse(FileChangeTracker.isTracking());
        assertTrue(FileChangeTracker.finish().isEmpty(), "reported once");
    }

    @Test
    @DisplayName("Outside a run nothing is tracked, and an edit undone within the run is not reported")
    void untrackedAndUndone() throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "x\n");
        new FileSystemTool().execute("{\"action\":\"write\",\"path\":\"a.txt\",\"content\":\"y\\n\"}");
        assertTrue(FileChangeTracker.finish().isEmpty());

        FileChangeTracker.begin(dir.toFile());
        new FileSystemTool().execute("{\"action\":\"write\",\"path\":\"a.txt\",\"content\":\"z\\n\"}");
        new FileSystemTool().execute("{\"action\":\"write\",\"path\":\"a.txt\",\"content\":\"y\\n\"}");
        assertTrue(FileChangeTracker.finish().isEmpty(), "back to how it was");
    }

    private static boolean git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
    }

    @Test
    @DisplayName("git repository: changes made outside the agent's tools are found too, counted from the start of the run")
    void gitFindsOtherChanges() throws Exception {
        boolean hasGit;
        try {
            hasGit = git(dir, "init", "-q");
        } catch (Exception e) {
            hasGit = false;
        }
        assumeTrue(hasGit, "git is not installed");
        Files.writeString(dir.resolve(".gitignore"), "target/\n");
        Files.writeString(dir.resolve("Main.java"), "a\nb\nc\n");
        Files.writeString(dir.resolve("gone.txt"), "1\n2\n");
        Files.writeString(dir.resolve("dirty.txt"), "v1\n");
        Files.writeString(dir.resolve("untouched-dirty.txt"), "v1\n");
        assertTrue(git(dir, "add", "-A"));
        assertTrue(git(dir, "commit", "-q", "-m", "init"));
        Files.writeString(dir.resolve("dirty.txt"), "v2\n");          // already changed before the run
        Files.writeString(dir.resolve("untouched-dirty.txt"), "v2\n");

        FileChangeTracker.begin(dir.toFile());
        Files.writeString(dir.resolve("Main.java"), "a\nB\nc\nd\n");   // e.g. by a shell command
        Files.writeString(dir.resolve("new.txt"), "n\n");
        Files.delete(dir.resolve("gone.txt"));
        Files.writeString(dir.resolve("dirty.txt"), "v2\nv3\n");
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target/Out.class"), "ignored build output");
        List<FileChange> changes = FileChangeTracker.finish();

        assertEquals(List.of(
                new FileChange("Main.java", 2, 1, Kind.MODIFIED),
                new FileChange("dirty.txt", 1, 0, Kind.MODIFIED),
                new FileChange("gone.txt", 0, 2, Kind.DELETED),
                new FileChange("new.txt", 1, 0, Kind.CREATED)), changes,
                "counted from the run's start; untouched files and ignored build output are left out");
    }

    @Test
    @DisplayName("Agent loop: the changed files are reported before the answer and saved after it in the session")
    void reportedByTheAgent() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            try {
                AgentLoopFixesTest.json(ex, count.getAndIncrement() == 0
                        ? AgentLoopFixesTest.toolCall("file_system", "{\"action\":\"write\",\"path\":\"hello.txt\",\"content\":\"hi\\nthere\\n\"}", 10)
                        : AgentLoopFixesTest.text("Done.", 10));
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
        AgentSession session = SessionManager.getInstance().createSession("Changes", PROVIDER, "gpt-4o", false);

        LangChainAgentService service = new LangChainAgentService();
        service.registerTool(new FileSystemTool());
        List<String> events = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        service.sendMessage("Write hello.txt", "", "", new AgentListener() {
            public void onToken(String t) { }
            public void onThinking(String t) { }
            public void onToolCall(String n, String i) { }
            public void onToolResult(String n, String o) { events.add("result " + o); }
            public void onFilesChanged(List<FileChange> changes) { events.add("files " + changes); }
            public void onComplete(String r) { events.add("complete"); done.countDown(); }
            public void onError(Throwable t) { events.add("error " + t.getMessage()); done.countDown(); }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS));

        assertTrue(events.get(0).startsWith("result SUCCESS") && events.get(0).endsWith("(+2 -0)"), events.toString());
        assertEquals("files [FileChange[path=hello.txt, added=2, removed=0, kind=CREATED]]", events.get(1));
        assertEquals("complete", events.get(2), "the summary comes before the answer is complete");
        List<AgentMessage> messages = session.getMessages();
        AgentMessage last = messages.get(messages.size() - 1);
        assertEquals(AgentRole.FILE_CHANGES, last.getRole());
        assertEquals("Changed 1 file (+2 -0)\nhello.txt +2 -0 (new)", last.getContent());
        assertEquals(AgentRole.ASSISTANT, messages.get(messages.size() - 2).getRole(), "saved after the answer");
        assertFalse(requests.get(1).contains("Changed 1 file"), "the summary is for the user, not resent to the model");
    }
}

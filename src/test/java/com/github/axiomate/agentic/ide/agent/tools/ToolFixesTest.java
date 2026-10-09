package com.github.axiomate.agentic.ide.agent.tools;

import com.github.axiomate.agentic.ide.features.security.IrreversibleActionGate;
import com.github.axiomate.agentic.ide.features.security.SecretLeakGuard;
import com.github.axiomate.agentic.ide.util.OSUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ToolFixesTest {

    @TempDir
    Path project;
    private File previousProject;

    @BeforeEach
    void setUp() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(project.toFile());
    }

    @AfterEach
    void tearDown() {
        IrreversibleActionGate.getInstance().setConfirmationHandler(null);
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    private static String bash(String command, int timeout) throws Exception {
        return new BashTool().execute("{\"command\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(command)
                + ",\"timeout_seconds\":" + timeout + "}");
    }

    // ------------------------------------------------------------------ shell tools

    @Test
    @DisplayName("A command that never exits is stopped at the timeout, children included")
    void timeoutStopsNeverEndingCommands() throws Exception {
        assumeFalse(OSUtils.isWindows());
        long start = System.currentTimeMillis();
        String result = bash("sleep 4321 & echo started; wait", 1);
        assertTrue(System.currentTimeMillis() - start < 6_000, "took " + (System.currentTimeMillis() - start) + " ms");
        assertTrue(result.startsWith("ERROR: Bash command timed out after 1 seconds"), result);
        assertTrue(result.contains("started"), "partial output kept: " + result);
        Thread.sleep(300);
        boolean orphan = ProcessHandle.allProcesses().anyMatch(p -> p.isAlive()
                && p.info().commandLine().orElse("").matches("\\S*sleep 4321"));
        assertFalse(orphan, "the background child must be killed too");
    }

    @Test
    @DisplayName("Large output is truncated without stalling the command; prompts don't hang")
    void largeOutputAndStdin() throws Exception {
        assumeFalse(OSUtils.isWindows());
        long start = System.currentTimeMillis();
        String big = bash("head -c 2000000 /dev/zero | tr '\\\\0' 'x'; echo; echo done", 30);
        assertTrue(big.startsWith("Exit code: 0"), big.substring(0, Math.min(200, big.length())));
        assertTrue(big.contains("[OUTPUT TRUNCATED"), "truncation is reported");
        assertTrue(System.currentTimeMillis() - start < 15_000, "a chatty command must not block on a full pipe");

        String prompt = bash("read -r answer; echo got:[$answer]", 30);
        assertTrue(prompt.startsWith("Exit code:"), prompt);
        assertTrue(prompt.contains("got:[]"), "stdin is closed, so read returns at once: " + prompt);
    }

    @Test
    @DisplayName("bash and powershell ask before destructive commands, like terminal does")
    void shellToolsUseTheDestructiveCommandGate() throws Exception {
        assumeFalse(OSUtils.isWindows());
        String blocked = bash("rm -rf build", 10);
        assertTrue(blocked.startsWith("BLOCKED"), "no confirmation handler: refused: " + blocked);

        List<String> asked = new CopyOnWriteArrayList<>();
        IrreversibleActionGate.getInstance().setConfirmationHandler((op, subject, warning) -> {
            asked.add(op + ":" + subject);
            return true;
        });
        Files.createDirectories(project.resolve("build/x"));
        String allowed = bash("rm -rf build", 10);
        assertTrue(allowed.startsWith("Exit code: 0"), allowed);
        assertFalse(Files.exists(project.resolve("build")));
        assertEquals(List.of("FILE_DELETION:rm -rf build"), asked, "the user is shown the command itself");

        String ps = new PowerShellTool().execute("{\"command\":\"git push --force\"}");
        assertTrue(asked.size() == 2 && asked.get(1).startsWith("FORCE_PUSH"), "powershell is gated too: " + asked);
        assertNotNull(ps);
    }

    @Test
    @DisplayName("Destructive-command detection: real force pushes and recursive deletes, not look-alikes")
    void gatePatterns() {
        IrreversibleActionGate gate = IrreversibleActionGate.getInstance();
        assertFalse(gate.evaluate("git push origin feature-fix", "").isDestructive(), "branch names with -f are fine");
        assertFalse(gate.evaluate("git push -u origin main", "").isDestructive());
        assertTrue(gate.evaluate("git push -f origin main", "").isDestructive());
        assertTrue(gate.evaluate("git push -uf origin main", "").isDestructive());
        assertTrue(gate.evaluate("git push --force-with-lease", "").isDestructive());
        assertTrue(gate.evaluate("rm -r target", "").isDestructive());
        assertTrue(gate.evaluate("rm -fr target", "").isDestructive());
        assertTrue(gate.evaluate("Remove-Item build -Recurse -Force", "").isDestructive());
        assertTrue(gate.evaluate("git clean -fd", "").isDestructive());
        assertFalse(gate.evaluate("rm notes.txt", "").isDestructive());
        assertFalse(gate.evaluate("mvn -f pom.xml test", "").isDestructive());
    }

    @Test
    @DisplayName("Secret guard ignores identifiers that merely contain 'sk-'")
    void secretGuardBoundaries() {
        SecretLeakGuard g = SecretLeakGuard.getInstance();
        assertFalse(g.scanAndSanitize("name: task-scheduler-configuration-default").leakDetected());
        assertFalse(g.scanAndSanitize("disk-image-builder-production-eu-west").leakDetected());
        assertTrue(g.scanAndSanitize("OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwxyz123456").leakDetected());
        assertEquals(1, g.scanAndSanitize("key: sk-ant-api03-abcdefghijklmnopqrstuvwxyz").secretCount(), "counted once");
        assertTrue(g.scanAndSanitize("aws AKIAIOSFODNN7EXAMPLE").leakDetected());
    }

    // ------------------------------------------------------------------ code_editor / code_refactor

    private String edit(String json) throws Exception {
        return new AutonomousCodeEditorTool().execute(json);
    }

    @Test
    @DisplayName("Line edits keep the file's line endings and final newline")
    void lineEditsPreserveFormatting() throws Exception {
        Path crlf = Files.writeString(project.resolve("Win.java"), "a\r\nb\r\nc\r\n");
        assertTrue(edit("{\"action\":\"replace_lines\",\"filePath\":\"Win.java\",\"startLine\":2,\"endLine\":2,\"replacement\":\"B1\\nB2\\n\"}")
                .startsWith("SUCCESS"));
        assertEquals("a\r\nB1\r\nB2\r\nc\r\n", Files.readString(crlf));

        Path lf = Files.writeString(project.resolve("Unix.java"), "one\ntwo\n");
        edit("{\"action\":\"delete_lines\",\"filePath\":\"Unix.java\",\"startLine\":1,\"endLine\":1}");
        assertEquals("two\n", Files.readString(lf));
        edit("{\"action\":\"insert_at_line\",\"filePath\":\"Unix.java\",\"line\":2,\"content\":\"three\"}");
        assertEquals("two\nthree\n", Files.readString(lf));

        Path noEol = Files.writeString(project.resolve("NoEol.txt"), "x\ny");
        edit("{\"action\":\"replace_lines\",\"filePath\":\"NoEol.txt\",\"startLine\":1,\"endLine\":1,\"replacement\":\"X\"}");
        assertEquals("X\ny", Files.readString(noEol), "no newline is added where there was none");
    }

    @Test
    @DisplayName("replace_content changes one unique match, works in CRLF files, and says when it is ambiguous")
    void replaceContentRules() throws Exception {
        Path f = Files.writeString(project.resolve("A.java"), "int x = 1;\nint y = 1;\nint x = 1;\n");
        String ambiguous = edit("{\"action\":\"replace_content\",\"filePath\":\"A.java\",\"target\":\"int x = 1;\",\"replacement\":\"int x = 2;\"}");
        assertTrue(ambiguous.contains("appears 2 times"), ambiguous);
        assertEquals("int x = 1;\nint y = 1;\nint x = 1;\n", Files.readString(f), "nothing changed");
        assertTrue(edit("{\"action\":\"replace_content\",\"filePath\":\"A.java\",\"target\":\"int x = 1;\",\"replacement\":\"int x = 2;\",\"replace_all\":true}")
                .startsWith("SUCCESS"));
        assertEquals("int x = 2;\nint y = 1;\nint x = 2;\n", Files.readString(f));

        Path win = Files.writeString(project.resolve("W.java"), "class W {\r\n  void a() {}\r\n}\r\n");
        assertTrue(edit("{\"action\":\"replace_content\",\"filePath\":\"W.java\",\"target\":\"class W {\\n  void a() {}\",\"replacement\":\"class W {\\n  void b() {}\"}")
                .startsWith("SUCCESS"));
        assertEquals("class W {\r\n  void b() {}\r\n}\r\n", Files.readString(win));
    }

    @Test
    @DisplayName("Edits leave existing text alone, mask only new credentials, and refuse non-UTF-8 files")
    void secretsAndEncoding() throws Exception {
        Path f = Files.writeString(project.resolve("Fixture.java"), "String demo = \"AKIAIOSFODNN7EXAMPLE\";\nint a;\n");
        edit("{\"action\":\"replace_lines\",\"filePath\":\"Fixture.java\",\"startLine\":2,\"endLine\":2,\"replacement\":\"int b;\"}");
        assertEquals("String demo = \"AKIAIOSFODNN7EXAMPLE\";\nint b;\n", Files.readString(f), "untouched line kept as is");
        edit("{\"action\":\"insert_at_line\",\"filePath\":\"Fixture.java\",\"line\":3,\"content\":\"String k = \\\"sk-proj-abcdefghijklmnopqrstuvwxyz123456\\\";\"}");
        assertFalse(Files.readString(f).contains("sk-proj-abcdefghijklmnopqrstuvwxyz123456"), "a new key is masked");

        Path latin1 = project.resolve("Legacy.txt");
        Files.write(latin1, "café\n".getBytes(StandardCharsets.ISO_8859_1));
        byte[] before = Files.readAllBytes(latin1);
        String refused = edit("{\"action\":\"replace_content\",\"filePath\":\"Legacy.txt\",\"target\":\"caf\",\"replacement\":\"bar\"}");
        assertTrue(refused.contains("not UTF-8"), refused);
        assertArrayEquals(before, Files.readAllBytes(latin1), "file not corrupted");
        assertTrue(edit("{\"action\":\"read_file\",\"filePath\":\"Legacy.txt\"}").contains("caf"), "still readable");
    }

    @Test
    @DisplayName("code_refactor with no code does not wipe the file; edits reach open editor tabs")
    void refactorAndEditorSync() throws Exception {
        Path f = Files.writeString(project.resolve("Keep.java"), "class Keep {}\n");
        String r = new CodeRefactorTool().execute("{\"filePath\":\"Keep.java\"}");
        assertTrue(r.startsWith("ERROR"), r);
        assertEquals("class Keep {}\n", Files.readString(f));

        List<String> notified = new CopyOnWriteArrayList<>();
        ProjectManager.getInstance().addFileContentListener((file, content) -> notified.add(file.getName()));
        new CodeRefactorTool().execute("{\"filePath\":\"Keep.java\",\"targetCode\":\"Keep {}\",\"replacementCode\":\"Keep { int v; }\"}");
        assertEquals("class Keep { int v; }\n", Files.readString(f));
        new FileSystemTool().execute("{\"action\":\"write\",\"path\":\"New.txt\",\"content\":\"hi\"}");
        assertTrue(notified.containsAll(List.of("Keep.java", "New.txt")), notified.toString());
    }

    // ------------------------------------------------------------------ file_system

    @Test
    @DisplayName("file_system: binary and huge files are reported, not dumped; writes stay inside the project")
    void fileSystemReadAndWriteRules(@TempDir Path elsewhere) throws Exception {
        Files.write(project.resolve("app.class"), new byte[]{(byte) 0xCA, (byte) 0xFE, 0, 0, 0, 65});
        assertTrue(new FileSystemTool().execute("{\"action\":\"read\",\"path\":\"app.class\"}").contains("binary file"));

        Files.writeString(project.resolve("huge.log"), "x".repeat(FileSystemTool.MAX_READ_BYTES + 5_000));
        String huge = new FileSystemTool().execute("{\"action\":\"read\",\"path\":\"huge.log\"}");
        assertTrue(huge.length() < FileSystemTool.MAX_READ_BYTES + 1_000);
        assertTrue(huge.contains("[TRUNCATED"));

        Files.write(project.resolve("mixed.txt"), new byte[]{'o', 'k', (byte) 0xE9, '\n'});
        assertTrue(new FileSystemTool().execute("{\"action\":\"read\",\"path\":\"mixed.txt\"}").startsWith("ok"),
                "a stray non-UTF-8 byte doesn't make the file unreadable");

        Path outside = elsewhere.resolve("evil.txt");
        String w = new FileSystemTool().execute("{\"action\":\"write\",\"path\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(outside.toString())
                + ",\"content\":\"x\"}");
        if (!outside.startsWith(System.getProperty("java.io.tmpdir"))) {
            assertTrue(w.startsWith("ERROR"), w);
            assertFalse(Files.exists(outside));
        }
    }
}

package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.util.OSUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UiFixesTest {

    @TempDir
    Path dir;
    private File previousProject;

    @BeforeEach
    void remember() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
    }

    @AfterEach
    void restore() {
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    @DisplayName("Files are matched by identity: '..' paths match, different case does not on Linux")
    void sameFile() throws Exception {
        Path a = Files.createDirectories(dir.resolve("src")).resolve("App.java");
        Files.writeString(a, "class App {}");
        assertTrue(EditorPanel.sameFile(a.toFile(), dir.resolve("src/../src/App.java").toFile()));
        if (!OSUtils.isWindows() && !new File(dir.toFile(), "SRC").exists()) {
            assertFalse(EditorPanel.sameFile(a.toFile(), dir.resolve("src/app.java").toFile()), "App.java and app.java differ");
        }
    }

    @Test
    @DisplayName("A Latin-1 file opens and saves back byte for byte; binary files are not opened")
    void encodingsAndBinaries() throws Exception {
        byte[] latin1 = "café = 1;\n".getBytes(StandardCharsets.ISO_8859_1);
        Path legacy = Files.write(dir.resolve("Legacy.java"), latin1);
        Path bin = Files.write(dir.resolve("lib.so"), new byte[]{0x7f, 'E', 'L', 'F', 0, 0});
        EditorPanel editor = new EditorPanel();
        SwingUtilities.invokeAndWait(() -> editor.openFile(legacy.toFile()));
        assertEquals("café = 1;\n", editor.getActiveText(), "decoded, not refused");
        SwingUtilities.invokeAndWait(editor::saveActiveFile);
        assertArrayEquals(latin1, Files.readAllBytes(legacy), "saved in its own encoding");

        SwingUtilities.invokeAndWait(() -> editor.openFile(bin.toFile()));
        assertEquals(1, editor.getOpenFiles().size(), "binary file not opened as text");
    }

    @Test
    @DisplayName("An agent edit does not silently discard unsaved edits; a clean tab is updated")
    void agentEditsAndUnsavedWork() throws Exception {
        Path f = Files.writeString(dir.resolve("Notes.java"), "v1\n");
        Path g = Files.writeString(dir.resolve("Other.java"), "o1\n");
        EditorPanel editor = new EditorPanel();
        SwingUtilities.invokeAndWait(() -> {
            editor.openFile(g.toFile());
            editor.openFile(f.toFile());
            editor.getActiveEditor().setText("my unsaved work\n");
        });
        assertEquals(List.of("Notes.java"), editor.getUnsavedFileNames());

        editor.reloadOrUpdateFile(f.toFile(), "agent version\n");
        editor.reloadOrUpdateFile(dir.resolve("x/../Other.java").toFile(), "o2\n");
        flushEdt();
        assertEquals("my unsaved work\n", editor.getActiveText(), "unsaved edits kept (no one to ask in headless mode)");
        SwingUtilities.invokeAndWait(() -> editor.selectFile(g.toFile()));
        assertEquals("o2\n", editor.getActiveText(), "a clean tab follows the agent, also via an unnormalized path");
    }

    @Test
    @DisplayName("Deleting a symbolic link in the Explorer leaves the linked folder alone")
    void deleteSymlinkOnly() throws Exception {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("precious.txt"), "keep me");
        Path link = dir.resolve("project-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            assumeTrue(false, "symbolic links not available: " + e.getMessage());
        }
        ProjectTreePanel.deleteRecursively(link.toFile());
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link is gone");
        assertTrue(Files.exists(outside.resolve("precious.txt")), "the target's files are untouched");

        Path real = Files.createDirectories(dir.resolve("build/classes"));
        Files.writeString(real.resolve("A.class"), "x");
        ProjectTreePanel.deleteRecursively(dir.resolve("build").toFile());
        assertFalse(Files.exists(dir.resolve("build")), "ordinary folders are still deleted recursively");
    }

    @Test
    @DisplayName("Terminal: cd is remembered, prompts don't hang, a running command can be stopped")
    void terminal() throws Exception {
        assumeFalse(OSUtils.isWindows());
        Files.createDirectories(dir.resolve("sub"));
        assertEquals(dir.resolve("sub").toFile(), TerminalPanel.resolveCd("cd sub", dir.toFile()));
        assertEquals(dir.toFile(), TerminalPanel.resolveCd("cd ..", dir.resolve("sub").toFile()));
        assertNull(TerminalPanel.resolveCd("cd sub && ls", dir.toFile()), "compound commands run in the shell");

        TerminalPanel terminal = new TerminalPanel();
        JTextArea console = findConsole(terminal);
        assertTrue(terminal.runCommand("cd sub"));
        assertTrue(terminal.runCommand("pwd; read -r x; echo got:[$x]"));
        waitFor(() -> console.getText().contains("exit code"), 10_000);
        assertTrue(console.getText().contains(dir.resolve("sub").toString()), console.getText());
        assertTrue(console.getText().contains("got:[]"), "stdin closed: " + console.getText());

        assertTrue(terminal.runCommand("sleep 600"));
        Thread.sleep(300);
        assertFalse(terminal.runCommand("echo second"), "one command at a time");
        terminal.stopRunningCommand();
        waitFor(() -> console.getText().contains("^C") && console.getText().split("exit code").length > 2, 10_000);
        assertTrue(terminal.runCommand("echo after-stop"));
        waitFor(() -> console.getText().contains("after-stop"), 10_000);
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            flushEdt();
            if (cond.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("timed out");
    }

    private static JTextArea findConsole(Container c) {
        List<JTextArea> areas = new ArrayList<>();
        collect(c, areas);
        return areas.get(0); // the Terminal tab's console comes first
    }

    private static void collect(Container c, List<JTextArea> out) {
        for (Component k : c.getComponents()) {
            if (k instanceof JTextArea a) out.add(a);
            if (k instanceof Container cc) collect(cc, out);
        }
    }
}

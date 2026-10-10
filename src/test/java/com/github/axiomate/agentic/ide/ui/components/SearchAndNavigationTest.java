package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.tools.SearchFilesTool;
import com.github.axiomate.agentic.ide.ui.dialogs.QuickOpenDialog;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import com.github.axiomate.agentic.ide.util.WorkspaceSearch;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Find/Replace, Find in Files, Quick Open, Go to Line, the search_files tool and prompt history. */
class SearchAndNavigationTest {

    @TempDir
    Path dir;
    private File previousProject;

    @BeforeEach
    void setUp() throws Exception {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        Files.createDirectories(dir.resolve("src/util"));
        Files.createDirectories(dir.resolve("src/application"));
        Files.createDirectories(dir.resolve("target/classes"));
        Files.createDirectories(dir.resolve("node_modules/lib"));
        Files.writeString(dir.resolve("src/App.java"), "class App {\n    void run() { log(\"run\"); }\n    Apple apple;\n}\n");
        Files.writeString(dir.resolve("src/util/Helper.kt"), "fun help() = run { 42 }\n");
        Files.writeString(dir.resolve("src/application/Main.java"), "class Main { /* runs App */ }\n");
        Files.writeString(dir.resolve("README.md"), "Run the app with mvn\n");
        Files.writeString(dir.resolve("target/classes/App.java"), "void run() {}\n");
        Files.writeString(dir.resolve("node_modules/lib/x.js"), "run()\n");
        Files.write(dir.resolve("data.bin"), new byte[]{'r', 'u', 'n', 0, 1, 2});
    }

    @AfterEach
    void restore() {
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    private WorkspaceSearch.Result search(String q, boolean regex, boolean matchCase, boolean wholeWord, String include)
            throws Exception {
        return WorkspaceSearch.search(dir, new WorkspaceSearch.Options(q, regex, matchCase, wholeWord, include, 100), () -> false);
    }

    private static List<String> where(WorkspaceSearch.Result r) {
        return r.matches().stream().map(m -> m.relativePath() + ":" + m.line()).toList();
    }

    // ---------------------------------------------------------------- workspace search

    @Test
    @DisplayName("Workspace search: one result per line, build/dependency folders and binary files skipped")
    void workspaceSearch() throws Exception {
        WorkspaceSearch.Result r = search("run", false, false, false, "");
        assertEquals(List.of("README.md:1", "src/App.java:2", "src/application/Main.java:1", "src/util/Helper.kt:1"), where(r));
        WorkspaceSearch.Match app = r.matches().get(1);
        assertEquals(9, app.column());
        assertEquals(3, app.length());
        assertEquals("    void run() { log(\"run\"); }", app.lineText());
        assertEquals(4, r.filesMatched());
        assertFalse(r.truncated());

        assertEquals(List.of("src/App.java:2", "src/util/Helper.kt:1"), where(search("run", false, true, true, "")),
                "case-sensitive whole word: not 'Run', not 'runs'");
        assertEquals(List.of("src/App.java:1", "src/application/Main.java:1"), where(search("App", false, true, true, "")),
                "'Apple' is not the word 'App'");
        assertEquals(List.of("src/App.java:2"), where(search("vo+id\\s+run", true, false, false, "")));
    }

    @Test
    @DisplayName("Include globs: file names, folders, ** and {a,b}; a result limit is reported")
    void includeGlobsAndLimit() throws Exception {
        assertEquals(List.of("src/App.java:2", "src/application/Main.java:1"), where(search("run", false, false, false, "*.java")));
        assertEquals(List.of("src/util/Helper.kt:1"), where(search("run", false, false, false, "src/util/")));
        assertEquals(List.of("src/App.java:2", "src/application/Main.java:1", "src/util/Helper.kt:1"),
                where(search("run", false, false, false, "src/**/*.{java,kt}")));
        assertEquals(List.of("README.md:1", "src/util/Helper.kt:1"), where(search("run", false, false, false, "*.md; *.kt")));
        assertTrue(WorkspaceSearch.includes("", "anything/at/all.txt"));

        WorkspaceSearch.Result limited = WorkspaceSearch.search(dir,
                new WorkspaceSearch.Options("run", false, false, false, "", 2), () -> false);
        assertEquals(2, limited.matches().size());
        assertTrue(limited.truncated());
    }

    @Test
    @DisplayName("Quick Open: files are listed without build output and ranked by file name first")
    void quickOpenRanking() throws Exception {
        List<String> files = WorkspaceSearch.listFiles(dir, 1000);
        assertEquals(List.of("data.bin", "README.md", "src/App.java", "src/application/Main.java", "src/util/Helper.kt"), files);
        assertEquals("src/util/Helper.kt", WorkspaceSearch.rankFiles(files, "hlp", 10).get(0));
        assertEquals("src/App.java", WorkspaceSearch.rankFiles(files, "app", 10).get(0), "the file name beats a folder name");
        assertEquals(List.of("src/application/Main.java"), WorkspaceSearch.rankFiles(files, "appl/main", 10));
        assertTrue(WorkspaceSearch.rankFiles(files, "zzz", 10).isEmpty());

        assertEquals(new QuickOpenDialog.Query("App.java", 42, 6), QuickOpenDialog.parse("App.java:42:7"));
        assertEquals(new QuickOpenDialog.Query("src/App", 3, 0), QuickOpenDialog.parse(" src/App:3 "));
        assertEquals(new QuickOpenDialog.Query("", 12, 0), QuickOpenDialog.parse(":12"));
        assertEquals(new QuickOpenDialog.Query("Helper", -1, 0), QuickOpenDialog.parse("Helper"));
    }

    // ---------------------------------------------------------------- search_files tool

    @Test
    @DisplayName("search_files tool: grep-style output, sub-folder and include filters, stays inside the project")
    void searchFilesTool() throws Exception {
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        SearchFilesTool tool = new SearchFilesTool();

        String out = tool.execute("{\"query\": \"run\", \"whole_word\": true, \"case_sensitive\": true}");
        assertTrue(out.startsWith("Found 2 matching line(s) in 2 file(s)"), out);
        assertTrue(out.contains("src/App.java:2: void run() { log(\"run\"); }"), out);
        assertTrue(out.contains("src/util/Helper.kt:1: fun help() = run { 42 }"), out);
        assertFalse(out.contains("target/"), out);

        String sub = tool.execute("{\"query\": \"run\", \"path\": \"src/util\"}");
        assertTrue(sub.contains("src/util/Helper.kt:1:"), "paths stay relative to the project: " + sub);
        assertTrue(tool.execute("{\"query\": \"run\", \"include\": \"*.md\"}").contains("README.md:1: Run the app"));
        assertTrue(tool.execute("plain text query: nothing").startsWith("No matches"));
        assertTrue(tool.execute("{\"query\": \"(\", \"regex\": true}").startsWith("ERROR: Invalid regular expression"));
        assertTrue(tool.execute("{\"query\": \"run\", \"path\": \"../\"}").startsWith("ERROR: 'path' must be inside the project"));
        assertTrue(tool.execute("{\"query\": \"\"}").startsWith("ERROR"));
    }

    // ---------------------------------------------------------------- find / replace bar

    private static FindReplaceBar bar(RSyntaxTextArea ta) {
        FindReplaceBar bar = new FindReplaceBar(() -> ta);
        bar.open(true);
        return bar;
    }

    @Test
    @DisplayName("Find bar: incremental search, next/previous with wrap-around and an 'n of m' position")
    void findBar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RSyntaxTextArea ta = new RSyntaxTextArea("foo bar foo\nFoo food\n");
            FindReplaceBar bar = bar(ta);
            bar.findField.setText("foo");
            assertEquals(0, ta.getSelectionStart(), "typing selects the first match");
            assertEquals("1 of 4", bar.status.getText());
            assertTrue(bar.findNext());
            assertEquals(8, ta.getSelectionStart());
            bar.findNext();
            bar.findNext();
            assertEquals("4 of 4", bar.status.getText());
            bar.findNext();
            assertEquals(0, ta.getSelectionStart(), "wraps to the first match");
            bar.findPrevious();
            assertEquals("4 of 4", bar.status.getText(), "and back to the last");

            bar.matchCase.doClick();
            bar.wholeWord.doClick();
            assertEquals(2, FindReplaceBar.findAll(ta.getText(), bar.pattern()).size(), "'Foo' and 'food' no longer match");

            bar.regex.doClick();
            bar.findField.setText("(");
            assertEquals("Invalid regex", bar.status.getText());
            bar.close();
            assertFalse(bar.isVisible());
        });
    }

    @Test
    @DisplayName("Replace: one match at a time, regex groups, and Replace All as a single undo step")
    void replace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String original = "int a = 1;\nint b = 2;\nint c = 3;\n";
            RSyntaxTextArea ta = new RSyntaxTextArea(original);
            ta.discardAllEdits();
            FindReplaceBar bar = bar(ta);
            bar.findField.setText("int");
            bar.replaceField.setText("long");
            assertTrue(bar.replaceCurrent());
            assertEquals("long a = 1;\nint b = 2;\nint c = 3;\n", ta.getText());
            assertEquals("int", ta.getSelectedText(), "the next match is selected");

            bar.regex.doClick();
            bar.findField.setText("(\\w+) = (\\d)");
            bar.replaceField.setText("$1 = $2$2");
            assertEquals(3, bar.replaceAll());
            assertEquals("long a = 11;\nint b = 22;\nint c = 33;\n", ta.getText());
            ta.undoLastAction();
            assertEquals("long a = 1;\nint b = 2;\nint c = 3;\n", ta.getText(), "Replace All undoes in one step");

            bar.regex.doClick();
            bar.findField.setText("$1");
            bar.replaceField.setText("C:\\x");
            assertEquals(0, bar.replaceAll(), "literal mode: '$1' is just text");
        });
    }

    @Test
    @DisplayName("Go to Line and open-at-match: line and column parsing, clamping, the match selected")
    void goToLine() throws Exception {
        assertArrayEquals(new int[]{42, 0}, EditorPanel.parseLineColumn("42"));
        assertArrayEquals(new int[]{42, 6}, EditorPanel.parseLineColumn(" 42:7 "));
        assertNull(EditorPanel.parseLineColumn("abc"));
        assertNull(EditorPanel.parseLineColumn("0"));
        assertNull(EditorPanel.parseLineColumn(null));

        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        SwingUtilities.invokeAndWait(() -> {
            EditorPanel editor = new EditorPanel();
            editor.openFileAt(dir.resolve("src/App.java").toFile(), 2, 9, 3);
            RSyntaxTextArea ta = editor.getActiveEditor();
            assertEquals("run", ta.getSelectedText());
            assertTrue(editor.goToLine(999, 0), "lines past the end go to the last line");
            assertEquals(ta.getLineCount() - 1, ta.getCaretLineNumber());
            editor.goToLine(3, 500);
            assertEquals(2, ta.getCaretLineNumber());
            assertEquals("    Apple apple;".length(), ta.getCaretOffsetFromLineStart(), "columns past the end stop at it");

            editor.showFindBar(false);
            assertTrue(editor.getFindBar().isVisible());
            assertFalse(editor.getFindBar().isReplaceVisible());
        });
    }

    // ---------------------------------------------------------------- find in files

    @Test
    @DisplayName("Find in Files panel: results grouped by file and opened at the match")
    void findInFilesPanel() throws Exception {
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        List<String> opened = new ArrayList<>();
        SearchPanel[] holder = new SearchPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new SearchPanel();
            holder[0].setOpenHandler((f, line, col, len) -> opened.add(f.getName() + ":" + line + ":" + col + ":" + len));
            holder[0].wholeWord.setSelected(true);
            holder[0].focusSearch("run");
        });
        SearchPanel panel = holder[0];
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && (panel.isSearching() || panel.getRows().isEmpty())) {
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(20);
        }
        SwingUtilities.invokeAndWait(() -> { });

        List<String> rows = panel.getRows().stream()
                .map(r -> r.match() == null ? "# " + r.relativePath() + " (" + r.count() + ")" : "  " + r.match().line())
                .toList();
        assertEquals(List.of("# README.md (1)", "  1", "# src/App.java (1)", "  2", "# src/util/Helper.kt (1)", "  1"), rows);

        SwingUtilities.invokeAndWait(() -> {
            JList<?> list = find(panel, JList.class);
            list.setSelectedIndex(2); // a file heading opens its first match
            list.getActionMap().get("open-result").actionPerformed(null);
        });
        assertEquals(List.of("App.java:2:9:3"), opened);
    }

    // ---------------------------------------------------------------- prompt history

    @Test
    @DisplayName("Prompt history: newest first, the draft comes back, repeats move to the end, capped")
    void promptHistory() {
        PromptHistory h = new PromptHistory();
        assertNull(h.previous("x"));
        h.add("first");
        h.add("second");
        h.add("  ");
        h.add("third");
        assertEquals("third", h.previous("my draft"));
        assertEquals("second", h.previous("ignored"));
        assertEquals("first", h.previous(""));
        assertNull(h.previous(""), "nothing older");
        assertEquals("second", h.next());
        assertEquals("third", h.next());
        assertEquals("my draft", h.next(), "past the newest the draft comes back");
        assertFalse(h.isBrowsing());
        assertNull(h.next());

        h.add("first");
        assertEquals(List.of("second", "third", "first"), h.entries());
        h.seed(List.of("old one", "second", "old two"));
        assertEquals(List.of("old one", "old two", "second", "third", "first"), h.entries());

        for (int i = 0; i < PromptHistory.MAX_ENTRIES + 10; i++) h.add("p" + i);
        assertEquals(PromptHistory.MAX_ENTRIES, h.entries().size());
        assertEquals("p" + (PromptHistory.MAX_ENTRIES + 9), h.entries().get(PromptHistory.MAX_ENTRIES - 1));
    }

    @Test
    @DisplayName("Chat box: Ctrl+Up recalls a sent prompt, Ctrl+Down returns to the draft")
    void chatPromptHistory() throws Exception {
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        AIAgentPanel panel = holder[0];
        SwingUtilities.invokeAndWait(() -> {
            JTextArea input = promptBox(panel);
            input.setText("/help");
            press(input, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK); // runs locally, no model call
            assertEquals("", input.getText());
            assertEquals("/help", panel.getPromptHistory().entries().get(panel.getPromptHistory().entries().size() - 1));

            input.setText("half-typed");
            press(input, KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK);
            assertEquals("/help", input.getText());
            press(input, KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK);
            assertEquals("half-typed", input.getText());

            input.setText("");
            press(input, KeyEvent.VK_UP, 0);
            assertEquals("/help", input.getText(), "plain Up works in an empty prompt box");
        });
    }

    private static void press(JComponent c, int key, int modifiers) {
        // Straight to the listeners, in the order Swing calls them: dispatchEvent would be redirected to the focus owner
        KeyEvent e = new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED);
        for (java.awt.event.KeyListener l : c.getKeyListeners()) l.keyPressed(e);
    }

    private static JTextArea promptBox(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JTextArea a && a.getToolTipText() != null && a.getToolTipText().startsWith("Type your prompt")) return a;
            if (k instanceof Container cc) {
                JTextArea found = promptBox(cc);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T find(Container c, Class<T> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) return type.cast(k);
            if (k instanceof Container cc) {
                T found = find(cc, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}

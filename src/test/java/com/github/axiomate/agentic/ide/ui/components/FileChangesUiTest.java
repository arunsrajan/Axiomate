package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.FileChangeTracker;
import com.github.axiomate.agentic.ide.agent.FileChangeTracker.FileChange;
import com.github.axiomate.agentic.ide.agent.FileChangeTracker.Kind;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The changed-files summary after an answer, and the +/- lines on each edit's tool result. */
class FileChangesUiTest {

    @TempDir
    Path dir;
    private File previousProject;

    @BeforeEach
    void setUp() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
    }

    @AfterEach
    void restore() {
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    private static List<JLabel> labels(Container c) {
        List<JLabel> out = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel l) out.add(l);
            if (k instanceof Container cc) out.addAll(labels(cc));
        }
        return out;
    }

    private static List<AIAgentPanel.MessageCard> cards(Container c) {
        List<AIAgentPanel.MessageCard> out = new ArrayList<>();
        for (Component k : c.getComponents()) {
            if (k instanceof AIAgentPanel.MessageCard m) out.add(m);
            else if (k instanceof Container cc) out.addAll(cards(cc));
        }
        return out;
    }

    private static String plain(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    @Test
    @DisplayName("Summary card: total and per-file +/- lines, new and deleted files, a file's name opens it")
    void summaryCard() throws Exception {
        SessionManager.getInstance().createSession("Changes UI", "MOCK", "mock-agent", false);
        List<File> opened = new ArrayList<>();
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new AIAgentPanel(() -> "", new TerminalPanel());
            holder[0].setFileOpener(opened::add);
        });
        SwingUtilities.invokeAndWait(() -> {
            AIAgentPanel panel = holder[0];
            panel.appendChangesCard(List.of(
                    new FileChange("src/App.java", 30, 5, Kind.MODIFIED),
                    new FileChange("README.md", 12, 0, Kind.CREATED),
                    new FileChange("old.txt", 0, 4, Kind.DELETED)));
            AIAgentPanel.MessageCard card = cards(panel).get(cards(panel).size() - 1);
            List<String> texts = labels(card).stream().map(l -> plain(l.getText() == null ? "" : l.getText())).toList();
            assertTrue(texts.contains("Changed 3 files  +42 −9"), texts.toString());
            assertTrue(texts.contains("src/App.java  +30 −5"), texts.toString());
            assertTrue(texts.contains("README.md  +12 −0  new"), texts.toString());
            assertTrue(texts.contains("old.txt  +0 −4  deleted"), texts.toString());
            assertEquals("Changed 3 files (+42 -9)\nsrc/App.java +30 -5\nREADME.md +12 -0 (new)\nold.txt +0 -4 (deleted)",
                    card.getRawText(), "Copy message gives the summary as text");

            JLabel app = labels(card).stream().filter(l -> l.getText() != null && l.getText().contains("src/App.java")).findFirst().orElseThrow();
            JLabel deleted = labels(card).stream().filter(l -> l.getText() != null && l.getText().contains("old.txt")).findFirst().orElseThrow();
            click(app);
            click(deleted);
            assertEquals(List.of(dir.resolve("src/App.java").toFile()), opened, "a deleted file cannot be opened");
            assertTrue(app.getText().contains("<span style='color:#"), "the counts are coloured");
        });
    }

    private static void click(JLabel l) {
        MouseEvent e = new MouseEvent(l, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false, MouseEvent.BUTTON1);
        for (java.awt.event.MouseListener m : l.getMouseListeners()) m.mouseClicked(e);
    }

    @Test
    @DisplayName("Reopening a session shows its change summaries, and each edit's tool result keeps its +/- lines")
    void reloadAndToolResults() throws Exception {
        AgentSession s = SessionManager.getInstance().createSession("Changes reload", "MOCK", "mock-agent", false);
        s.addMessage(new AgentMessage(AgentRole.USER, "add notes"));
        s.addMessage(new AgentMessage(AgentRole.TOOL_CALL, "{\"action\":\"write\",\"path\":\"notes.txt\"}", "file_system"));
        s.addMessage(new AgentMessage(AgentRole.TOOL, "SUCCESS: Wrote 8 characters to " + dir.resolve("notes.txt") + " (+2 -0)", "file_system"));
        s.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Added the notes."));
        s.addMessage(new AgentMessage(AgentRole.FILE_CHANGES, FileChangeTracker.describe(
                List.of(new FileChange("notes.txt", 2, 0, Kind.CREATED)))));
        AIAgentPanel[] holder = new AIAgentPanel[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new AIAgentPanel(() -> "", new TerminalPanel()));
        SwingUtilities.invokeAndWait(() -> {
            AIAgentPanel panel = holder[0];
            panel.reloadChatFromSession();
            List<AIAgentPanel.MessageCard> cards = cards(panel);
            AIAgentPanel.MessageCard last = cards.get(cards.size() - 1);
            assertTrue(last.getRawText().startsWith("Changed 1 file (+2 -0)"), "the summary follows the answer: " + last.getRawText());

            List<String> texts = labels(panel).stream().map(l -> l.getText() == null ? "" : l.getText()).toList();
            assertTrue(texts.stream().anyMatch(t -> t.startsWith("SUCCESS: Wrote 8 characters") && !t.contains("(+2")),
                    "the counts leave the result's title: " + texts);
            assertTrue(texts.stream().anyMatch(t -> t.startsWith("<html>") && plain(t).equals("+2 −0")),
                    "and show beside it in colour: " + texts);
        });
    }
}

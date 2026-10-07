package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExplorerAndSessionFolderTest {

    private File previousProject;

    @org.junit.jupiter.api.BeforeEach
    void rememberProject() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    @org.junit.jupiter.api.AfterEach
    void restoreProject() {
        File back = previousProject != null ? previousProject : new File(System.getProperty("user.dir"));
        ProjectManager.getInstance().setCurrentProjectDirectory(back);
        SessionManager.getInstance().loadSessionsForProject(back);
    }

    @Test
    void explorerListsEveryFileIncludingHiddenAndBuildFolders(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main"));
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve(".env"), "x");
        Files.writeString(dir.resolve("README.md"), "x");
        Files.writeString(dir.resolve("src/main/App.java"), "x");

        List<String> names = new ArrayList<>();
        for (File f : ProjectTreePanel.listEntries(dir.toFile())) names.add(f.getName());
        assertEquals(List.of("src", "target", ".env", "README.md"), names, "folders first, then files, hidden ones included");

        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        ProjectTreePanel panel = new ProjectTreePanel(f -> { }, f -> { });
        JTree tree = findTree(panel);
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        assertEquals(4, root.getChildCount());

        DefaultMutableTreeNode src = (DefaultMutableTreeNode) root.getChildAt(0);
        tree.expandPath(new TreePath(src.getPath()));
        DefaultMutableTreeNode main = (DefaultMutableTreeNode) src.getChildAt(0);
        tree.expandPath(new TreePath(main.getPath()));
        assertEquals("App.java", main.getChildAt(0).toString(), "expanding a folder loads its files");
    }

    @Test
    void sessionsRememberTheirFolderAndSelectionShowsIt(@TempDir Path dir) {
        SessionManager sm = SessionManager.getInstance();
        sm.loadSessionsForProject(dir.toFile());
        AgentSession s = sm.createSession("Folder test", "MOCK", "mock-agent");
        assertEquals(dir.toFile().getAbsolutePath(), s.getProjectPath());

        AtomicReference<AgentSession> shown = new AtomicReference<>();
        IdeActions actions = new IdeActions() {
            @Override
            public void showSessionFolder(AgentSession session) {
                shown.set(session);
            }
        };
        SessionsPanel panel = new SessionsPanel(actions);
        JList<?> list = findList(panel);
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (list.getModel().getElementAt(i) == s) list.setSelectedIndex(i);
        }
        list.getActionMap().get("open").actionPerformed(null);
        assertSame(s, shown.get(), "opening a session asks the frame to show its folder");
        assertSame(s, sm.getActiveSession());
    }

    private static JTree findTree(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof JTree t) return t;
            if (k instanceof java.awt.Container cc) {
                JTree t = findTree(cc);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static JList<?> findList(java.awt.Container c) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof JList<?> l) return l;
            if (k instanceof java.awt.Container cc) {
                JList<?> l = findList(cc);
                if (l != null) return l;
            }
        }
        return null;
    }
}

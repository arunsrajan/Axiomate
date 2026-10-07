package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProjectGroupsTest {

    @TempDir
    Path root;
    private File previousProject;
    private File other;

    @BeforeEach
    void remember() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    @AfterEach
    void restore() {
        if (other != null) ProjectStateManager.getInstance().forgetProject(ProjectStateManager.normalizePath(other));
        File back = previousProject != null ? previousProject : new File(System.getProperty("user.dir"));
        ProjectManager.getInstance().setCurrentProjectDirectory(back);
        SessionManager.getInstance().loadSessionsForProject(back);
    }

    private static File gitProject(Path dir, String branch) throws Exception {
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/HEAD"), "ref: refs/heads/" + branch + "\n");
        return dir.toFile();
    }

    /** Rows as text: "# name [branch] n" for headings, "  session" for sessions. */
    private static List<String> rows(SessionsPanel panel) {
        JList<?> list = find(panel, JList.class);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Object v = list.getModel().getElementAt(i);
            if (v instanceof SessionsPanel.ProjectGroup g) {
                out.add("# " + g.name() + " [" + g.branch() + "] " + g.sessions().size());
            } else if (v instanceof AgentSession s) {
                out.add("  " + s.getName());
            }
        }
        return out;
    }

    @Test
    @DisplayName("Sessions are grouped under their projects with each project's branch; groups collapse; search spans all")
    void groupsByProject() throws Exception {
        File api = gitProject(root.resolve("payments-api"), "feature/refunds");
        other = gitProject(root.resolve("web-app"), "main");
        AgentSession saved = new AgentSession("Fix qzx login form", "MOCK", "mock-agent", 128_000);
        ProjectStateManager.getInstance().saveProjectSessions(other, new ArrayList<>(List.of(saved)), saved.getId());

        ProjectManager.getInstance().setCurrentProjectDirectory(api);
        SessionManager.getInstance().loadSessionsForProject(api);
        for (AgentSession s : new ArrayList<>(SessionManager.getInstance().getSessions())) {
            if (!s.getName().equals("Refunds")) SessionManager.getInstance().closeSession(s.getId());
        }
        SessionManager.getInstance().renameSession(SessionManager.getInstance().getSessions().get(0).getId(), "Refunds");

        AtomicReference<AgentSession> shown = new AtomicReference<>();
        SessionsPanel panel = new SessionsPanel(new IdeActions() {
            @Override
            public void showSessionFolder(AgentSession session) {
                shown.set(session);
            }
        });
        panel.reloadAll();

        List<String> r = rows(panel);
        assertEquals("# payments-api [feature/refunds] 1", r.get(0), "the open project comes first");
        assertEquals("  Refunds", r.get(1));
        int webIdx = r.indexOf("# web-app [main] 1");
        assertTrue(webIdx > 1, r.toString());
        assertEquals("  Fix qzx login form", r.get(webIdx + 1));

        // Clicking a heading collapses and expands the group
        JList<?> list = find(panel, JList.class);
        list.setSelectedIndex(webIdx);
        list.getActionMap().get("open").actionPerformed(null);
        assertFalse(rows(panel).contains("  Fix qzx login form"), "collapsed");
        JTextField search = find(panel, JTextField.class);
        search.setText("qzx");
        assertEquals(List.of("# web-app [main] 1", "  Fix qzx login form"), rows(panel), "search finds sessions in collapsed groups");
        search.setText("");
        list.setSelectedIndex(rows(panel).indexOf("# web-app [main] 1"));
        list.getActionMap().get("open").actionPerformed(null);
        assertTrue(rows(panel).contains("  Fix qzx login form"), "expanded again");

        // Opening a session of another project hands it over with its folder, so the frame can switch project
        list.setSelectedIndex(rows(panel).indexOf("  Fix qzx login form"));
        list.getActionMap().get("open").actionPerformed(null);
        assertNotNull(shown.get());
        assertEquals(saved.getId(), shown.get().getId());
        assertEquals(other.getAbsolutePath(), shown.get().getProjectPath());
    }

    @Test
    @DisplayName("The agent panel header shows the project and follows branch switches")
    void agentPanelShowsBranch() throws Exception {
        File api = gitProject(root.resolve("payments-api"), "main");
        ProjectManager.getInstance().setCurrentProjectDirectory(api);
        AIAgentPanel panel = new AIAgentPanel(() -> "", new TerminalPanel());
        panel.updateProjectBranch();
        assertEquals("payments-api", panel.shownProject());
        assertEquals("main", panel.shownBranch());

        Files.writeString(root.resolve("payments-api/.git/HEAD"), "ref: refs/heads/release/2.0\n");
        panel.updateProjectBranch();
        assertEquals("release/2.0", panel.shownBranch());

        File plain = Files.createDirectories(root.resolve("notes")).toFile();
        ProjectManager.getInstance().setCurrentProjectDirectory(plain);
        panel.updateProjectBranch();
        assertEquals("notes", panel.shownProject());
        assertNull(panel.shownBranch(), "no branch outside a git repository");
    }

    @SuppressWarnings("unchecked")
    private static <T> T find(Container c, Class<T> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) return (T) k;
            if (k instanceof Container cc) {
                T t = find(cc, type);
                if (t != null) return t;
            }
        }
        return null;
    }
}

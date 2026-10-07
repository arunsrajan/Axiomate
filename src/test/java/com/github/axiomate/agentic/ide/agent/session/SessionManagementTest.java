package com.github.axiomate.agentic.ide.agent.session;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.config.ProjectState;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagementTest {

    @Test
    @DisplayName("Fork copies history into an independent session with a new id")
    void forkIsIndependent() {
        AgentSession original = new AgentSession("Architect", "ANTHROPIC", "claude", 200_000);
        original.addMessage(new AgentMessage(AgentRole.USER, "Design it"));
        AgentSession copy = original.fork("Architect (copy)");

        assertNotEquals(original.getId(), copy.getId());
        assertEquals(1, copy.getMessages().size());
        copy.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Sure"));
        assertEquals(1, original.getMessages().size(), "original history untouched");
        assertTrue(copy.getOrigin().contains("Architect"));
        assertEquals(200_000, copy.getTokenTracker().getMaxContextTokens());
    }

    @Test
    @DisplayName("Sessions can be duplicated, pinned, imported idempotently, copied and deleted across projects")
    void crossProjectOperations(@TempDir Path tmp) throws IOException {
        File alpha = Files.createDirectories(tmp.resolve("alpha")).toFile();
        File beta = Files.createDirectories(tmp.resolve("beta")).toFile();
        SessionManager sm = SessionManager.getInstance();

        sm.loadSessionsForProject(beta);
        AgentSession betaSession = sm.createSession("Beta Agent", "OPENAI", "gpt-4o");
        sm.saveSessionsForProject(beta);

        sm.loadSessionsForProject(alpha);
        AgentSession a = sm.createSession("Alpha Agent", "ANTHROPIC", "claude-3-7-sonnet");
        a.addMessage(new AgentMessage(AgentRole.USER, "hello"));

        AgentSession dup = sm.duplicateSession(a.getId());
        assertNotNull(dup);
        assertEquals(dup.getId(), sm.getActiveSession().getId());
        assertEquals(1, dup.getMessages().size());

        sm.setPinned(a.getId(), true);
        assertTrue(sm.findSession(a.getId()).isPinned());

        AgentSession imported = new AgentSession("Claude Code: fix", "ANTHROPIC", "claude", 200_000);
        imported.setId("claude-code-xyz");
        int before = sm.getSessions().size();
        sm.addImportedSession(imported, false);
        sm.addImportedSession(imported, false);
        assertEquals(before + 1, sm.getSessions().size(), "re-importing replaces the session");

        // Other project listing does not switch projects
        List<AgentSession> betaSessions = sm.getSessionsForProject(beta);
        assertTrue(betaSessions.stream().anyMatch(s -> s.getId().equals(betaSession.getId())));
        assertEquals(ProjectStateManager.normalizePath(alpha), ProjectStateManager.normalizePath(sm.getCurrentProjectDirectory()));

        AgentSession copied = sm.copySessionToProject(a, beta);
        assertNotNull(copied);
        assertTrue(sm.getSessionsForProject(beta).stream().anyMatch(s -> s.getId().equals(copied.getId())));

        sm.deleteSessionFromProject(beta, copied.getId());
        assertTrue(sm.getSessionsForProject(beta).stream().noneMatch(s -> s.getId().equals(copied.getId())));
        assertTrue(sm.getSessionsForProject(beta).stream().anyMatch(s -> s.getId().equals(betaSession.getId())));

        // Known projects include both folders, most recent first
        sm.saveSessionsForProject(alpha);
        ProjectStateManager.getInstance().saveProjectState(alpha, List.of(), null);
        List<ProjectState> known = ProjectStateManager.getInstance().getKnownProjects();
        assertTrue(known.stream().anyMatch(p -> p.getProjectPath().equals(ProjectStateManager.normalizePath(alpha))));
        assertTrue(known.stream().noneMatch(p -> ProjectStateManager.DEFAULT_WORKSPACE_KEY.equals(p.getProjectPath())));
    }

    @Test
    @DisplayName("ProjectStateManager can forget a project")
    void forgetProject(@TempDir Path tmp) throws IOException {
        ProjectStateManager psm = new ProjectStateManager(tmp.resolve("state.json"));
        File dir = Files.createDirectories(tmp.resolve("gamma")).toFile();
        psm.saveProjectState(dir, List.of(), null);
        String key = ProjectStateManager.normalizePath(dir);
        assertEquals(1, psm.getKnownProjects().size());
        assertTrue(psm.forgetProject(key));
        assertTrue(psm.getKnownProjects().isEmpty());
        assertEquals("", psm.getLastOpenProjectPath());
    }
}

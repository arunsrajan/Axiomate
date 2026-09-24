package com.github.axiomate.agentic.ide.agent.session;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.ui.components.AIAgentPanel;
import com.github.axiomate.agentic.ide.ui.components.TerminalPanel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSessionAndDisplayFilterTest {

    @BeforeAll
    static void setupHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("SessionManager should persist and isolate multiple entire sessions per project")
    void testPerProjectSessionSaveAndLoad(@TempDir Path tempDir) throws IOException {
        Path projectA = tempDir.resolve("project-alpha");
        Path projectB = tempDir.resolve("project-beta");
        Files.createDirectories(projectA);
        Files.createDirectories(projectB);

        SessionManager sm = SessionManager.getInstance();

        // 1. Setup Project A with multiple sessions
        sm.loadSessionsForProject(projectA.toFile());
        AgentSession sessA1 = sm.createSession("Design Agent", "ANTHROPIC", "claude-3-7-sonnet");
        sessA1.addMessage(new AgentMessage(AgentRole.USER, "Design the architecture"));
        sessA1.addMessage(new AgentMessage(AgentRole.TOOL_CALL, "{\"path\":\"docs\"}", "list_files"));
        sessA1.addMessage(new AgentMessage(AgentRole.TOOL, "docs/architecture.md", "list_files"));
        sessA1.addMessage(new AgentMessage(AgentRole.THINKING, "Evaluating clean architecture patterns..."));
        sessA1.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Architecture design completed."));

        AgentSession sessA2 = sm.createSession("Review Agent", "OPENAI", "gpt-4o");
        sessA2.addMessage(new AgentMessage(AgentRole.USER, "Review PR #42"));
        sessA2.addMessage(new AgentMessage(AgentRole.ASSISTANT, "LGTM!"));

        sm.saveSessionsForProject(projectA.toFile());

        // Verify local .axiomate/sessions.json was created for projectA
        File localAFile = projectA.resolve(".axiomate").resolve("sessions.json").toFile();
        assertTrue(localAFile.exists(), "Local project-level sessions.json should exist");

        // 2. Setup Project B with separate session
        sm.loadSessionsForProject(projectB.toFile());
        AgentSession sessB1 = sm.createSession("Beta Fixer Agent", "GEMINI", "gemini-2.0-flash");
        sessB1.addMessage(new AgentMessage(AgentRole.USER, "Fix null pointer in Beta"));
        sessB1.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Fixed with Optional check"));
        sm.saveSessionsForProject(projectB.toFile());

        // 3. Switch back to Project A and verify all sessions and messages restored
        sm.loadSessionsForProject(projectA.toFile());
        List<AgentSession> restoredA = sm.getSessions();
        assertTrue(restoredA.size() >= 2, "Project A should have restored at least 2 sessions");

        AgentSession restoredDesign = restoredA.stream()
                .filter(s -> "Design Agent".equals(s.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(restoredDesign, "Design Agent session should be restored");
        assertEquals("ANTHROPIC", restoredDesign.getProviderId());
        assertEquals("claude-3-7-sonnet", restoredDesign.getModelId());

        List<AgentMessage> messages = restoredDesign.getMessages();
        assertEquals(5, messages.size(), "Should have restored all 5 messages");
        assertEquals(AgentRole.USER, messages.get(0).getRole());
        assertEquals(AgentRole.TOOL_CALL, messages.get(1).getRole());
        assertEquals("list_files", messages.get(1).getToolName());
        assertEquals(AgentRole.TOOL, messages.get(2).getRole());
        assertEquals("list_files", messages.get(2).getToolName());
        assertEquals(AgentRole.THINKING, messages.get(3).getRole());
        assertTrue(messages.get(3).getContent().contains("clean architecture"));
        assertEquals(AgentRole.ASSISTANT, messages.get(4).getRole());

        // 4. Switch back to Project B and verify Project B's session restored
        sm.loadSessionsForProject(projectB.toFile());
        List<AgentSession> restoredB = sm.getSessions();
        assertTrue(restoredB.stream().anyMatch(s -> "Beta Fixer Agent".equals(s.getName())));
    }

    @Test
    @DisplayName("SessionManager should export and import sessions to and from JSON files")
    void testExportAndImportSessions(@TempDir Path tempDir) throws IOException {
        SessionManager sm = SessionManager.getInstance();
        AgentSession sess = sm.createSession("Exportable Agent", "CUSTOM", "qwen2.5-coder");
        sess.addMessage(new AgentMessage(AgentRole.USER, "Export test"));
        sess.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Export response"));

        File exportFile = tempDir.resolve("custom-sessions.json").toFile();
        sm.exportSessionsToFile(exportFile);

        assertTrue(exportFile.exists(), "Export file should exist on disk");
        assertTrue(exportFile.length() > 0, "Export file should not be empty");

        // Clear and import back
        sm.closeSession(sess.getId());
        sm.importSessionsFromFile(exportFile, false);

        assertTrue(sm.getSessions().stream().anyMatch(s -> "Exportable Agent".equals(s.getName())),
                "Imported sessions should contain Exportable Agent");
    }

    @Test
    @DisplayName("AIAgentPanel should support show/hide filtering and collapse/expand for all agent outputs")
    void testOutputDisplayPanelShowHideAndCollapse() {
        AIAgentPanel panel = new AIAgentPanel(() -> "class Test {}", new TerminalPanel());

        // Retrieve components from panel
        JPanel chatBox = null;
        for (Component c : panel.getComponents()) {
            if (c instanceof JScrollPane sp && sp.getViewport().getView() instanceof JPanel p) {
                chatBox = p;
                break;
            }
        }
        assertNotNull(chatBox, "ChatBox panel should exist inside AIAgentPanel scroll pane");

        // Simulate session messages
        SessionManager sm = SessionManager.getInstance();
        AgentSession sess = sm.getActiveSession();
        sess.clearMessages();
        sess.addMessage(new AgentMessage(AgentRole.USER, "Test prompt"));
        sess.addMessage(new AgentMessage(AgentRole.TOOL_CALL, "{\"arg\":1}", "buildTool"));
        sess.addMessage(new AgentMessage(AgentRole.TOOL, "Build Success", "buildTool"));
        sess.addMessage(new AgentMessage(AgentRole.THINKING, "Deep thinking..."));
        sess.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Final walkthrough summary"));

        panel.reloadChatFromSession();

        // Count MessageCards
        int toolRequestCards = 0;
        int toolResponseCards = 0;
        int thinkingCards = 0;
        int walkthroughCards = 0;

        for (Component c : chatBox.getComponents()) {
            if (c instanceof AIAgentPanel.MessageCard card) {
                switch (card.getDisplayType()) {
                    case TOOL_REQUEST -> toolRequestCards++;
                    case TOOL_RESPONSE -> toolResponseCards++;
                    case THINKING -> thinkingCards++;
                    case WALKTHROUGH -> walkthroughCards++;
                    default -> {}
                }
            }
        }

        assertTrue(toolRequestCards >= 1, "Should have rendered TOOL_REQUEST card");
        assertTrue(toolResponseCards >= 1, "Should have rendered TOOL_RESPONSE card");
        assertTrue(thinkingCards >= 1, "Should have rendered THINKING card");
        assertTrue(walkthroughCards >= 1, "Should have rendered WALKTHROUGH card");

        // Test collapse all
        panel.collapseAllCards();
        for (Component c : chatBox.getComponents()) {
            if (c instanceof AIAgentPanel.MessageCard card && card.getDisplayType() != AIAgentPanel.MessageDisplayType.USER) {
                assertTrue(card.isCollapsed(), "Cards should be collapsed after collapseAllCards");
            }
        }

        // Test expand all
        panel.expandAllCards();
        for (Component c : chatBox.getComponents()) {
            if (c instanceof AIAgentPanel.MessageCard card && card.getDisplayType() != AIAgentPanel.MessageDisplayType.USER) {
                assertFalse(card.isCollapsed(), "Cards should be expanded after expandAllCards");
            }
        }
    }
}

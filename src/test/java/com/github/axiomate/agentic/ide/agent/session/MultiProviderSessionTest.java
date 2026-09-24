package com.github.axiomate.agentic.ide.agent.session;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.router.AutonomousTaskRouter;
import com.github.axiomate.agentic.ide.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MultiProviderSessionTest {

    @BeforeEach
    void setUp() {
        ConfigManager.getInstance().resetToDefaults();
        MemoryManager.getInstance().clear();
    }

    @Test
    @DisplayName("Verify Anthropic, OpenAI, Gemini and Custom URLs are configurable with multiple models per provider")
    void testMultiProviderConfiguration() {
        IdeConfig config = ConfigManager.getInstance().getConfig();

        // 1. Anthropic Configuration & Models
        ProviderConfig anthropic = config.getProvider("ANTHROPIC");
        assertNotNull(anthropic, "Anthropic provider must exist");
        assertEquals("https://api.anthropic.com/v1", anthropic.getBaseUrl());
        assertTrue(anthropic.getModels().size() >= 3, "Anthropic should support multiple models");
        assertNotNull(anthropic.findModel("claude-3-7-sonnet"));
        assertNotNull(anthropic.findModel("claude-3-5-sonnet"));
        assertEquals(200_000, anthropic.findModel("claude-3-7-sonnet").getMaxContextTokens());

        // Custom URL configuration test
        anthropic.setBaseUrl("https://custom-anthropic-proxy.corp.internal/v1");
        assertEquals("https://custom-anthropic-proxy.corp.internal/v1", anthropic.getBaseUrl());

        // 2. OpenAI Configuration & Models
        ProviderConfig openai = config.getProvider("OPENAI");
        assertNotNull(openai, "OpenAI provider must exist");
        assertEquals("https://api.openai.com/v1", openai.getBaseUrl());
        assertTrue(openai.getModels().size() >= 3, "OpenAI should support multiple models");
        assertNotNull(openai.findModel("gpt-4o"));
        assertNotNull(openai.findModel("o1"));
        assertEquals(128_000, openai.findModel("gpt-4o").getMaxContextTokens());

        // 3. Google Gemini Configuration & Models
        ProviderConfig gemini = config.getProvider("GEMINI");
        assertNotNull(gemini, "Gemini provider must exist");
        assertEquals("https://generativelanguage.googleapis.com/v1beta", gemini.getBaseUrl());
        assertTrue(gemini.getModels().size() >= 3, "Gemini should support multiple models");
        assertNotNull(gemini.findModel("gemini-2.0-flash"));
        assertNotNull(gemini.findModel("gemini-1.5-pro"));
        assertEquals(1_000_000, gemini.findModel("gemini-2.0-flash").getMaxContextTokens());
        assertEquals(2_000_000, gemini.findModel("gemini-1.5-pro").getMaxContextTokens());
    }

    @Test
    @DisplayName("Verify Multi-Agent Sessions creation, switching, renaming and isolation")
    void testMultiAgentSessions() {
        SessionManager manager = SessionManager.getInstance();

        // 1. Create Session 1: Claude Code Architect
        AgentSession session1 = manager.createSession("Claude Code Architect", "ANTHROPIC", "claude-3-7-sonnet");
        assertNotNull(session1);
        assertEquals("Claude Code Architect", session1.getName());
        assertEquals("ANTHROPIC", session1.getProviderId());
        assertEquals("claude-3-7-sonnet", session1.getModelId());
        assertEquals(200_000, session1.getTokenTracker().getMaxContextTokens());

        // Add message to session 1
        session1.addMessage(new AgentMessage(AgentRole.USER, "Design a reactive streaming pipeline"));
        assertEquals(1, session1.getMessages().size());
        assertTrue(session1.getTokenTracker().getTotalTokens() > 0);

        // 2. Create Session 2: Gemini Fast Explainer
        AgentSession session2 = manager.createSession("Gemini Fast Explainer", "GEMINI", "gemini-2.0-flash");
        assertNotNull(session2);
        assertEquals("Gemini Fast Explainer", session2.getName());
        assertEquals("GEMINI", session2.getProviderId());
        assertEquals(1_000_000, session2.getTokenTracker().getMaxContextTokens());
        assertEquals(0, session2.getMessages().size(), "New session should have isolated empty history");

        // 3. Verify Active Session Switching
        assertEquals(session2, manager.getActiveSession());
        manager.switchSession(session1.getId());
        assertEquals(session1, manager.getActiveSession());

        // 4. Verify Rename
        manager.renameSession(session1.getId(), "Claude Master Architect");
        assertEquals("Claude Master Architect", session1.getName());
    }

    @Test
    @DisplayName("Verify TokenTracker usage and 95% threshold calculation")
    void testTokenTrackerLimits() {
        TokenTracker tracker = new TokenTracker(100_000); // 100k max context limit
        assertEquals(0, tracker.getTotalTokens());
        assertEquals(0.0, tracker.getUsagePercentage(), 0.01);
        assertFalse(tracker.isThresholdReached(0.95));

        // Add 50,000 tokens
        tracker.recordUsage(30_000, 20_000);
        assertEquals(50_000, tracker.getTotalTokens());
        assertEquals(50.0, tracker.getUsagePercentage(), 0.01);
        assertFalse(tracker.isThresholdReached(0.95));

        // Add 46,000 more tokens -> 96,000 total (96% >= 95%)
        tracker.recordUsage(40_000, 6_000);
        assertEquals(96_000, tracker.getTotalTokens());
        assertEquals(96.0, tracker.getUsagePercentage(), 0.01);
        assertTrue(tracker.isThresholdReached(0.95), "95% threshold must be reached at 96% usage");
    }

    @Test
    @DisplayName("Verify Context Compression Utility when 95% limit is reached")
    void testContextCompressionAt95Percent() {
        AgentSession session = new AgentSession("Long Conversation Agent", "OPENAI", "gpt-4o", 10_000);

        // Populate session with 8 detailed conversation turns
        for (int i = 1; i <= 8; i++) {
            session.addMessage(new AgentMessage(AgentRole.USER, "Step " + i + ": Implement part " + i + " of the algorithm with error handling."));
            session.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Analysis for step " + i + ": The implementation requires synchronized state and defensive checks."));
        }

        assertEquals(16, session.getMessages().size());

        // Set token usage to 9,600 (96% of 10,000 limit)
        session.getTokenTracker().setEstimatedUsage(9_600);
        assertTrue(session.getTokenTracker().isThresholdReached(0.95));

        // Trigger Context Compression
        ContextCompressor.CompressionResult result = ContextCompressor.compressIfExceeded(session, 0.95);

        assertTrue(result.compressed(), "Compression must execute when usage >= 95%");
        assertTrue(result.tokensSaved() > 0, "Tokens saved must be greater than zero");
        assertTrue(session.getMessages().size() < 16, "Message history must be condensed");
        assertTrue(session.getTokenTracker().getUsagePercentage() < 95.0, "Token usage should drop significantly below 95%");

        // Verify summary was saved to episodic agent memory
        var memories = MemoryManager.getInstance().getMemoryStore().getAllMemories();
        assertFalse(memories.isEmpty(), "Episodic memory item must be recorded from compression");
        assertTrue(memories.get(0).getContent().contains("Condensed Context Summary"));
    }

    @Test
    @DisplayName("Verify Autonomous Task Routing automatically selects provider and model by task type")
    void testAutonomousTaskRouting() {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        config.setAutoRoutingEnabled(true);

        // 1. Refactor Task -> Anthropic Claude
        AutonomousTaskRouter.RoutedModel refactorRoute = AutonomousTaskRouter.route(
                "Refactor and modernize this class for cleaner architecture", "MOCK", "mock-agent");
        assertEquals(TaskType.REFACTOR, refactorRoute.taskType());
        assertEquals("ANTHROPIC", refactorRoute.providerId());
        assertEquals("claude-3-7-sonnet", refactorRoute.modelId());

        // 2. Explain Task -> Gemini Flash
        AutonomousTaskRouter.RoutedModel explainRoute = AutonomousTaskRouter.route(
                "Explain how this concurrent queue functions in detail", "MOCK", "mock-agent");
        assertEquals(TaskType.EXPLAIN, explainRoute.taskType());
        assertEquals("GEMINI", explainRoute.providerId());
        assertEquals("gemini-2.0-flash", explainRoute.modelId());

        // 3. Test Generation Task -> OpenAI GPT-4o
        AutonomousTaskRouter.RoutedModel testRoute = AutonomousTaskRouter.route(
                "Generate comprehensive JUnit 5 unit tests with assertions", "MOCK", "mock-agent");
        assertEquals(TaskType.GENERATE_TESTS, testRoute.taskType());
        assertEquals("OPENAI", testRoute.providerId());
        assertEquals("gpt-4o", testRoute.modelId());

        // 4. Debug Task -> Anthropic Claude
        AutonomousTaskRouter.RoutedModel debugRoute = AutonomousTaskRouter.route(
                "Find this bug causing a NullPointerException in line 42", "MOCK", "mock-agent");
        assertEquals(TaskType.DEBUG_FIX, debugRoute.taskType());
        assertEquals("ANTHROPIC", debugRoute.providerId());
    }
}


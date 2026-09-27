package com.github.axiomate.agentic.ide.features;

import com.github.axiomate.agentic.ide.features.collaboration.*;
import com.github.axiomate.agentic.ide.features.devexperience.*;
import com.github.axiomate.agentic.ide.features.extensibility.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering Collaboration (37-41), Customization & Extensibility (42-45),
 * and Developer Experience (46-50).
 */
public class CollaborationAndDevExFeaturesTest {

    @Test
    @DisplayName("Feature 37: Multiplayer Agent Sessions coordinates peers and active intents")
    void testMultiplayerSessionManager() {
        MultiplayerSessionManager manager = MultiplayerSessionManager.getInstance();
        assertNotNull(manager);

        assertFalse(manager.getActivePeers().isEmpty());

        CollaborativePeer customPeer = new CollaborativePeer(
                "peer-carol", "Carol (QA)", "carol@axiomate.io",
                "AppTest.java:L10", "Running regression suite", Instant.now(), "#E36209"
        );
        manager.registerPeer(customPeer);

        assertTrue(manager.getActivePeers().stream().anyMatch(p -> p.peerId().equals("peer-carol")));
        manager.updatePeerIntent("peer-carol", "Verifying assertions", "AppTest.java:L25");
        CollaborativePeer updated = manager.getActivePeers().stream().filter(p -> p.peerId().equals("peer-carol")).findFirst().orElse(null);
        assertNotNull(updated);
        assertEquals("AppTest.java:L25", updated.cursorPosition());
    }

    @Test
    @DisplayName("Feature 38: Agent Handoff Notes synthesizes structured context for transfers")
    void testAgentHandoffNotesService() {
        AgentHandoffNotesService service = AgentHandoffNotesService.getInstance();
        assertNotNull(service);

        HandoffBrief brief = service.generateHandoffBrief(
                "Implement Redis Caching", "Agent-Alpha", "Senior Engineer",
                List.of("CacheManager.java", "pom.xml"), "Benchmarked 20% throughput speedup"
        );

        assertNotNull(brief);
        assertEquals("Implement Redis Caching", brief.taskTitle());
        assertFalse(brief.workCompletedSoFar().isEmpty());
        assertFalse(brief.keyDecisionsMade().isEmpty());
        assertFalse(brief.openQuestionsAndRisks().isEmpty());
        assertNotNull(brief.toMarkdown());
    }

    @Test
    @DisplayName("Feature 39: PR Review Copilot answers comments and prepares patches")
    void testPrReviewCopilot() {
        PrReviewCopilot copilot = PrReviewCopilot.getInstance();
        assertNotNull(copilot);

        ReviewResponseProposal propNpe = copilot.processReviewerComment(
                "senior-reviewer", "Could this method throw a NullPointerException if input is null?", "UserService.java"
        );
        assertNotNull(propNpe);
        assertTrue(propNpe.codeModificationRequired());
        assertTrue(propNpe.draftedAuthorReply().contains("Objects.requireNonNull"));
        assertNotNull(propNpe.proposedCodePatchDiff());

        ReviewResponseProposal propThread = copilot.processReviewerComment(
                "lead-architect", "Is this state thread safe?", "UserService.java"
        );
        assertNotNull(propThread);
        assertFalse(propThread.codeModificationRequired());
        assertTrue(propThread.draftedAuthorReply().contains("ConcurrentHashMap"));
    }

    @Test
    @DisplayName("Feature 40: Team Conventions Memory learns accepted style and anti-patterns")
    void testTeamConventionsMemoryService() {
        TeamConventionsMemoryService service = TeamConventionsMemoryService.getInstance();
        assertNotNull(service);

        assertFalse(service.getConventions().isEmpty());

        TeamConvention custom = new TeamConvention(
                "conv-test-naming",
                "TESTING",
                "Unit test methods should follow shouldDoSomethingWhenCondition() pattern",
                "void shouldReturnTrueWhenValid()",
                "void testValid()",
                95,
                Instant.now(),
                "PR #45 (Accepted)"
        );
        service.learnConvention(custom);

        assertTrue(service.getConventions().stream().anyMatch(c -> c.conventionId().equals("conv-test-naming")));
    }

    @Test
    @DisplayName("Feature 41: Stakeholder Summaries generates PM updates and release notes")
    void testStakeholderSummaryGenerator() {
        StakeholderSummaryGenerator generator = StakeholderSummaryGenerator.getInstance();
        assertNotNull(generator);

        StakeholderSummary summary = generator.generateSummaries("OAuth2 Single Sign-On", "Migrated auth filters to OAuth2 standard JWT");
        assertNotNull(summary);
        assertNotNull(summary.executiveLeadershipBrief());
        assertNotNull(summary.productManagerUpdate());
        assertNotNull(summary.customerReleaseNotes());
        assertNotNull(summary.supportTeamIncidentBrief());
        assertFalse(summary.keyDeliverables().isEmpty());
    }

    @Test
    @DisplayName("Feature 42: Custom Agent Roles defines specialist instructions and tools")
    void testCustomAgentRolesRegistry() {
        CustomAgentRolesRegistry registry = CustomAgentRolesRegistry.getInstance();
        assertNotNull(registry);

        assertFalse(registry.getAllRoles().isEmpty());
        Optional<AgentRoleDefinition> secRole = registry.getRole("role-security");
        assertTrue(secRole.isPresent());
        assertEquals("Security Reviewer", secRole.get().roleName());
        assertFalse(secRole.get().allowedTools().isEmpty());

        AgentRoleDefinition newRole = new AgentRoleDefinition(
                "role-graphql", "GraphQL Specialist", "Optimizes GraphQL resolvers and N+1 queries",
                "You are a GraphQL Specialist.", List.of("code_editor"), "BALANCED", List.of("GraphQL")
        );
        registry.registerRole(newRole);
        assertTrue(registry.getRole("role-graphql").isPresent());
    }

    @Test
    @DisplayName("Feature 43: Workflow Recorder turns multi-step developer actions into reusable skills")
    void testWorkflowRecorderSkillService() {
        WorkflowRecorderSkillService service = WorkflowRecorderSkillService.getInstance();
        assertNotNull(service);

        service.startRecording();
        assertTrue(service.isRecording());

        service.recordAction("RUN_COMMAND", "mvn test", Map.of("timeout", "30s"));
        service.recordAction("EDIT_FILE", "pom.xml", Map.of("action", "bump-version"));

        assertEquals(2, service.getCurrentSessionActions().size());

        AgentSkill skill = service.stopRecordingAndSynthesize("Version Bump Workflow", "Cleans, runs tests, and bumps version in pom.xml");
        assertNotNull(skill);
        assertEquals("Version Bump Workflow", skill.skillName());
        assertEquals(2, skill.steps().size());
        assertFalse(service.isRecording());
    }

    @Test
    @DisplayName("Feature 44: Tool and Connector Marketplace installs and activates integrations")
    void testToolConnectorMarketplace() {
        ToolConnectorMarketplace marketplace = ToolConnectorMarketplace.getInstance();
        assertNotNull(marketplace);

        assertFalse(marketplace.getAllConnectors().isEmpty());

        Optional<MarketplaceConnector> jiraOpt = marketplace.getConnector("conn-jira");
        assertTrue(jiraOpt.isPresent());
        MarketplaceConnector jira = jiraOpt.get();
        assertTrue(jira.installed());

        marketplace.setConnectorInstalled("conn-jira", false);
        assertFalse(marketplace.getConnector("conn-jira").get().installed());

        marketplace.setConnectorInstalled("conn-jira", true);
        assertTrue(marketplace.getConnector("conn-jira").get().installed());
    }

    @Test
    @DisplayName("Feature 45: Policy-as-Code engine enforces organizational rules and blocks")
    void testPolicyAsCodeEngine() {
        PolicyAsCodeEngine engine = PolicyAsCodeEngine.getInstance();
        assertNotNull(engine);

        assertFalse(engine.getPolicies().isEmpty());

        // Billing directory requires approval
        PolicyValidationResult billingRes = engine.evaluate("src/main/java/com/example/billing/StripeAdapter.java", "WRITE");
        assertNotNull(billingRes);
        assertFalse(billingRes.allowed());
        assertEquals("REQUIRE_APPROVAL", billingRes.enforcementLevel());

        // Production properties write is denied
        PolicyValidationResult prodRes = engine.evaluate("src/main/resources/application-prod.properties", "WRITE");
        assertFalse(prodRes.allowed());
        assertEquals("DENY", prodRes.enforcementLevel());

        // Benign file is allowed
        PolicyValidationResult okRes = engine.evaluate("src/main/java/com/example/ui/Theme.java", "WRITE");
        assertTrue(okRes.allowed());
        assertEquals("ALLOW", okRes.enforcementLevel());
    }

    @Test
    @DisplayName("Feature 46: Voice and sketch input processes speech and UI wireframes")
    void testVoiceSketchInputProcessor() {
        VoiceSketchInputProcessor processor = VoiceSketchInputProcessor.getInstance();
        assertNotNull(processor);

        // Voice transcription
        VoiceSketchResult voice = processor.processVoiceTranscription("um so basically add a validation method to Calculator dot java");
        assertNotNull(voice);
        assertEquals("VOICE_TRANSCRIPTION", voice.inputType());
        assertFalse(voice.synthesizedPrompt().contains("basically"));
        assertTrue(voice.synthesizedPrompt().contains("Calculator.java"));

        // Whiteboard ASCII sketch
        String sketch = """
                +---------------------------------------+
                |  [ Search Input Field ]  [ Button ]   |
                |  +---------------------------------+  |
                |  | Results Table List              |  |
                |  +---------------------------------+  |
                +---------------------------------------+
                """;
        VoiceSketchResult ui = processor.processUiSketch(sketch);
        assertNotNull(ui);
        assertEquals("UI_SKETCH_WIREFRAME", ui.inputType());
        assertFalse(ui.inferredUiComponents().isEmpty());
    }

    @Test
    @DisplayName("Feature 47: Confidence heatmap computes line-by-line model certainty")
    void testConfidenceHeatmapService() {
        ConfidenceHeatmapService service = ConfidenceHeatmapService.getInstance();
        assertNotNull(service);

        String sampleCode = """
                package com.example;
                // Standard imports
                import java.util.List;
                
                public class Service {
                    public void execute() {
                        int x = 10;
                    }
                }
                """;

        HeatmapReport report = service.generateHeatmap("Service.java", sampleCode);
        assertNotNull(report);
        assertTrue(report.averageConfidence() > 0.0);
        assertTrue(report.totalLines() > 0);
        assertFalse(report.lines().isEmpty());
    }

    @Test
    @DisplayName("Feature 48: Learning mode synthesizes educational mini-lessons")
    void testLearningModeEngine() {
        LearningModeEngine engine = LearningModeEngine.getInstance();
        assertNotNull(engine);
        assertTrue(engine.isLearningModeEnabled());

        LearningLesson lesson = engine.createLesson("Switched to immutable record with defensive checks", "public record User(String name) {}");
        assertNotNull(lesson);
        assertNotNull(lesson.lessonTitle());
        assertNotNull(lesson.coreConcept());
        assertNotNull(lesson.designPatternExplained());
        assertFalse(lesson.keyTakeaways().isEmpty());
        assertNotNull(lesson.interactiveChallengeQuestion());
    }

    @Test
    @DisplayName("Feature 49: Focus guardian batches non-urgent agent questions")
    void testFocusGuardianService() {
        FocusGuardianService guardian = FocusGuardianService.getInstance();
        assertNotNull(guardian);
        guardian.setFocusModeActive(true);
        assertTrue(guardian.isFocusModeActive());

        // Low / normal priority gets queued
        boolean lowImmediate = guardian.postOrQueue("QUESTION", "Naming clarification", "Do you prefer Dto or Payload suffix?", "NORMAL");
        assertFalse(lowImmediate);
        assertFalse(guardian.getQueuedNotifications().isEmpty());

        // Urgent interrupts focus mode
        boolean urgentImmediate = guardian.postOrQueue("ALERT", "Security Breached", "Production API key exposed", "URGENT");
        assertTrue(urgentImmediate);

        // Disabling focus mode flushes batch
        guardian.setFocusModeActive(false);
        assertFalse(guardian.isFocusModeActive());
        assertTrue(guardian.getQueuedNotifications().isEmpty());
    }

    @Test
    @DisplayName("Feature 50: Agent analytics dashboard tracks acceptance, rework, cost, and time saved")
    void testAgentAnalyticsDashboard() {
        AgentAnalyticsDashboard dashboard = AgentAnalyticsDashboard.getInstance();
        assertNotNull(dashboard);

        AgentMetrics metrics = dashboard.computeMetrics();
        assertNotNull(metrics);
        assertTrue(metrics.totalTasksExecuted() > 0);
        assertTrue(metrics.acceptanceRatePercent() > 0);
        assertTrue(metrics.estimatedHoursSaved() > 0);
        assertTrue(metrics.totalCostUsd() > 0);
        assertFalse(metrics.failurePatternsPerTaskType().isEmpty());
        assertFalse(metrics.latencyPerModelMs().isEmpty());

        // Record a new task outcome
        dashboard.recordTaskOutcome("REFACTOR", true, 30.0, 0.04, null);
        AgentMetrics updated = dashboard.computeMetrics();
        assertTrue(updated.totalTasksExecuted() > metrics.totalTasksExecuted());
    }
}

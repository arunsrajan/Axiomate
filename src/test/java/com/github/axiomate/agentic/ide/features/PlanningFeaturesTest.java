package com.github.axiomate.agentic.ide.features;

import com.github.axiomate.agentic.ide.features.planning.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering Planning & Task Understanding features (Features 1-6).
 */
public class PlanningFeaturesTest {

    @Test
    @DisplayName("Feature 1: Intent Clarification Loop detects ambiguities and assumptions")
    void testIntentClarificationLoop() {
        IntentClarificationService service = IntentClarificationService.getInstance();
        assertNotNull(service);

        List<ClarificationQuestion> questions = service.analyzeIntent("Migrate user auth to JWT and persist in database", "src/Auth.java");
        assertNotNull(questions);
        assertFalse(questions.isEmpty());

        for (ClarificationQuestion q : questions) {
            assertNotNull(q.id());
            assertNotNull(q.question());
            assertNotNull(q.assumedDefault());
            assertFalse(q.options().isEmpty());
        }

        // Resolving an ambiguity and synthesizing clarified prompt
        String clarified = service.synthesizeClarifiedPrompt("Migrate user auth to JWT and persist in database", questions);
        assertNotNull(clarified);
        assertTrue(clarified.contains("Clarified Intent"));
    }

    @Test
    @DisplayName("Feature 2: Living Plan Canvas manages editable, reorderable steps")
    void testLivingPlanCanvas() {
        LivingPlanCanvas canvas = LivingPlanCanvas.getInstance();
        assertNotNull(canvas);

        canvas.generatePlanFromPrompt("Refactor Module to modern records", "src/User.java");
        assertNotNull(canvas.getPlanTitle());
        assertFalse(canvas.getTasks().isEmpty());

        PlanTask newTask = new PlanTask(
                "task-custom-99",
                "Perform final verification",
                "Run sanity and unit test verification",
                List.of("src/UserTest.java"),
                List.of("Assertion failures"),
                99
        );
        canvas.addTask(newTask);

        // Reordering
        canvas.moveTaskUp(canvas.getTasks().size() - 1);
        canvas.updateTaskStatus("task-custom-99", PlanStatus.IN_PROGRESS);

        PlanTask retrieved = canvas.findTask("task-custom-99");
        assertNotNull(retrieved);
        assertEquals(PlanStatus.IN_PROGRESS, retrieved.getStatus());
    }

    @Test
    @DisplayName("Feature 3: Blast-Radius Preview computes affected files, APIs, and risk")
    void testBlastRadiusPreview() {
        BlastRadiusAnalyzer analyzer = BlastRadiusAnalyzer.getInstance();
        assertNotNull(analyzer);

        BlastRadiusResult result = analyzer.analyze("src/main/java/com/github/axiomate/agentic/ide/agent/session/SessionManager.java", "Refactor session state and persistence");
        assertNotNull(result);
        assertNotNull(result.riskLevel());
        assertTrue(result.impactScore() >= 0 && result.impactScore() <= 100);
        assertFalse(result.directlyModifiedFiles().isEmpty());
        assertFalse(result.affectedApis().isEmpty());
        assertNotNull(result.downstreamServices());
        assertNotNull(result.rationale());
    }

    @Test
    @DisplayName("Feature 4: Effort and Cost Estimator predicts tokens, latency, and confidence")
    void testEffortAndCostEstimator() {
        EffortCostEstimator estimator = EffortCostEstimator.getInstance();
        assertNotNull(estimator);

        EffortEstimate estimate = estimator.estimate("Implement payment webhook", "public class WebhookHandler {}", "ANTHROPIC");

        assertNotNull(estimate);
        assertTrue(estimate.estimatedDurationSeconds() > 0);
        assertTrue(estimate.estimatedInputTokens() > 0);
        assertTrue(estimate.estimatedOutputTokens() > 0);
        assertTrue(estimate.estimatedCostUsd() >= 0);
        assertTrue(estimate.confidenceScore() >= 0 && estimate.confidenceScore() <= 1.0);
        assertNotNull(estimate.getFormattedDuration());
        assertNotNull(estimate.getFormattedCost());
    }

    @Test
    @DisplayName("Feature 5: Spec-to-Ticket Decomposition breaks PRD into sized subtasks")
    void testSpecToTicketDecomposition() {
        SpecToTicketDecomposer decomposer = SpecToTicketDecomposer.getInstance();
        assertNotNull(decomposer);

        String prd = """
                # Payment Gateway Integration
                ## Goal
                Support Stripe checkout and webhook callbacks.
                ## Tasks
                1. Create Stripe SDK client adapter
                2. Implement webhook signature verification
                3. Update order state in database
                4. Write integration tests
                """;

        List<Ticket> tickets = decomposer.decompose("Stripe Integration", prd);
        assertNotNull(tickets);
        assertFalse(tickets.isEmpty());

        for (Ticket t : tickets) {
            assertNotNull(t.id());
            assertNotNull(t.title());
            assertNotNull(t.tShirtSize());
            assertNotNull(t.targetAgentRole());
        }
    }

    @Test
    @DisplayName("Feature 6: 'Why this approach' Rationale includes rejected alternatives")
    void testApproachRationaleEngine() {
        ApproachRationaleEngine engine = ApproachRationaleEngine.getInstance();
        assertNotNull(engine);

        ApproachRationale rationale = engine.generateRationale(
                "Implement distributed concurrency with async event bus",
                "CacheManager.java"
        );

        assertNotNull(rationale);
        assertNotNull(rationale.chosenApproachTitle());
        assertFalse(rationale.chosenApproachBenefits().isEmpty());
        assertFalse(rationale.rejectedAlternatives().isEmpty());
    }
}

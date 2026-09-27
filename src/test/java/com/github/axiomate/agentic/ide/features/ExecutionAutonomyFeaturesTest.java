package com.github.axiomate.agentic.ide.features;

import com.github.axiomate.agentic.ide.features.execution.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering Execution & Autonomy features (Features 7-14).
 */
public class ExecutionAutonomyFeaturesTest {

    @Test
    @DisplayName("Feature 7: Autonomy dial per task and directory risk")
    void testAutonomyDial() {
        AutonomyDial dial = AutonomyDial.getInstance();
        assertNotNull(dial);

        dial.setPathOverride("src/main/resources/auth", AutonomyLevel.SUGGEST_ONLY);
        assertEquals(AutonomyLevel.SUGGEST_ONLY, dial.resolveLevel(null, "src/main/resources/auth/keys.pem", null));

        dial.setGlobalLevel(AutonomyLevel.FULLY_AUTONOMOUS);
        assertEquals(AutonomyLevel.FULLY_AUTONOMOUS, dial.resolveLevel(null, "src/main/java/Utils.java", null));
        assertTrue(dial.canExecuteWithoutApproval(null, "src/main/java/Utils.java", null));
    }

    @Test
    @DisplayName("Feature 8: Parallel agent swarm with merge arbitration")
    void testAgentSwarmCoordinator() {
        AgentSwarmCoordinator coordinator = AgentSwarmCoordinator.getInstance();
        assertNotNull(coordinator);

        SwarmWorker worker1 = new SwarmWorker("w1", "Worker-1", "Refactorer", "/tmp/wt1",
                Map.of("FileA.java", "public class FileA { int x = 1; }"), true);
        SwarmWorker worker2 = new SwarmWorker("w2", "Worker-2", "DocWriter", "/tmp/wt2",
                Map.of("FileB.java", "/** docs */ public class FileB {}"), true);

        ArbitrationResult result = coordinator.runSwarmWithArbitration(List.of(worker1, worker2));

        assertNotNull(result);
        assertNotNull(result.summary());
        assertFalse(result.arbitrationNotes().isEmpty());
        assertTrue(result.unifiedChangeset().containsKey("FileA.java"));
        assertTrue(result.unifiedChangeset().containsKey("FileB.java"));
    }

    @Test
    @DisplayName("Feature 9: Speculative branches benchmark and select winner")
    void testSpeculativeBranchManager() {
        SpeculativeBranchManager manager = SpeculativeBranchManager.getInstance();
        assertNotNull(manager);

        SpeculativeBranchManager.SpeculativeEvaluationResult evalResult =
                manager.evaluateSpeculativeBranches("Process string list", "public List<String> process(...) {}");

        assertNotNull(evalResult);
        assertFalse(evalResult.branches().isEmpty());
        assertNotNull(evalResult.winningBranch());
        assertNotNull(evalResult.comparisonSummary());
        assertTrue(evalResult.winningBranch().benchmark().overallScore() > 0);
    }

    @Test
    @DisplayName("Feature 10: Checkpoint and time-travel snapshots and forks")
    void testCheckpointTimeTravel() {
        CheckpointTimeTravelManager manager = CheckpointTimeTravelManager.getInstance();
        assertNotNull(manager);

        String sessionId = "test-session-" + System.currentTimeMillis();
        AgentCheckpoint cp1 = manager.createCheckpoint(sessionId, "Step 1: Init", Map.of("A.java", "code1"), "A.java");
        assertNotNull(cp1);
        assertEquals(1, cp1.stepNumber());

        AgentCheckpoint cp2 = manager.createCheckpoint(sessionId, "Step 2: Add Logic", Map.of("A.java", "code2"), "A.java");
        assertNotNull(cp2);
        assertEquals(2, cp2.stepNumber());

        List<AgentCheckpoint> list = manager.getCheckpoints(sessionId);
        assertEquals(2, list.size());

        boolean rewound = manager.rewindToCheckpoint(sessionId, cp1.checkpointId());
        assertNotNull(manager.getCheckpoint(sessionId, cp1.checkpointId()));
    }

    @Test
    @DisplayName("Feature 11: Long-running background jobs run asynchronously")
    void testBackgroundJobEngine() {
        BackgroundJobEngine engine = BackgroundJobEngine.getInstance();
        assertNotNull(engine);

        BackgroundJob job = engine.submitJob("Database Migration v2", "MIGRATION", () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        });

        assertNotNull(job);
        assertNotNull(job.getJobId());
        assertEquals("Database Migration v2", job.getJobName());
        assertFalse(engine.getAllJobs().isEmpty());
    }

    @Test
    @DisplayName("Feature 12: Self-healing CI diagnoses pipeline failure and proposes fix")
    void testSelfHealingCi() {
        SelfHealingCiService ciService = SelfHealingCiService.getInstance();
        assertNotNull(ciService);

        String logOutput = """
                [ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
                [ERROR] testJwtTokenValidation  Time elapsed: 0.045 s  <<< FAILURE!
                java.lang.AssertionError: expected: <200> but was: <401>
                    at com.example.AuthTest.testJwtTokenValidation(AuthTest.java:42)
                """;

        CiDiagnosis diagnosis = ciService.diagnoseAndHeal(logOutput);
        assertNotNull(diagnosis);
        assertNotNull(diagnosis.rootCauseSummary());
        assertNotNull(diagnosis.proposedFix().proposedDiff());
        assertNotNull(diagnosis.proposedFix().generatedPrTitle());
    }

    @Test
    @DisplayName("Feature 13: Scheduled maintenance agents trigger on schedule")
    void testScheduledMaintenanceManager() {
        ScheduledMaintenanceManager manager = ScheduledMaintenanceManager.getInstance();
        assertNotNull(manager);

        List<MaintenanceTask> tasks = manager.getTasks();
        assertNotNull(tasks);
        assertFalse(tasks.isEmpty());

        MaintenanceTask task = tasks.get(0);
        String report = manager.runTaskNow(task.getId());
        assertNotNull(report);
        assertEquals("SUCCESS", task.getLastRunStatus());
    }

    @Test
    @DisplayName("Feature 14: Human-in-the-loop breakpoints pause on critical paths")
    void testHumanInTheLoopGate() {
        HumanInTheLoopGate gate = HumanInTheLoopGate.getInstance();
        assertNotNull(gate);

        gate.addRule(new BreakpointRule(
                "rule-auth-test",
                "Security sensitive auth edits",
                "*auth*",
                "ALL",
                "Paused: Editing authentication subsystem requires human approval."
        ));

        Optional<BreakpointRule> hit = gate.checkBreakpoint("src/main/java/com/example/auth/TokenService.java", "EDIT");
        assertTrue(hit.isPresent());
        assertEquals("bp-auth", hit.get().id());

        boolean shouldPause = gate.shouldPauseForApproval("src/main/java/com/example/auth/TokenService.java", "EDIT");
        assertTrue(shouldPause);

        Optional<BreakpointRule> safe = gate.checkBreakpoint("src/main/java/com/example/ui/Theme.java", "EDIT");
        assertTrue(safe.isEmpty());
    }
}

package com.github.axiomate.agentic.ide.features;

import com.github.axiomate.agentic.ide.features.debugging.*;
import com.github.axiomate.agentic.ide.features.testing.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering Testing & Verification (Features 21-26) and Debugging & Runtime (Features 27-30).
 */
public class TestingAndDebuggingFeaturesTest {

    @Test
    @DisplayName("Feature 21: Test-First Mode executes Red-Green-Refactor cycle")
    void testTestFirstModeEngine() {
        TestFirstModeEngine engine = TestFirstModeEngine.getInstance();
        assertNotNull(engine);

        TddCycleReport report = engine.executeTddCycle("Order processing with discount calculation", "OrderService");
        assertNotNull(report);
        assertNotNull(report.generatedTestCode());
        assertNotNull(report.generatedImplementationCode());
        assertTrue(report.finalTestsPassed());
    }

    @Test
    @DisplayName("Feature 22: Mutation Testing on agent-generated code")
    void testMutationTestingEngine() {
        MutationTestingEngine engine = MutationTestingEngine.getInstance();
        assertNotNull(engine);

        String sampleCode = """
                public boolean isValid(int x, int y) {
                    if (x == 0) return false;
                    if (y != 10) return true;
                    return x < y;
                }
                """;
        String sampleTest = "@Test void testValid() { assertTrue(isValid(1, 5)); }";

        MutationResult result = engine.runMutationTest(sampleCode, sampleTest);
        assertNotNull(result);
        assertFalse(result.mutants().isEmpty());
        assertTrue(result.mutationScorePercent() >= 0 && result.mutationScorePercent() <= 100);
        assertNotNull(result.rating());
    }

    @Test
    @DisplayName("Feature 23: Property-based test generation with invariant fuzzing")
    void testPropertyBasedTestGenerator() {
        PropertyBasedTestGenerator generator = PropertyBasedTestGenerator.getInstance();
        assertNotNull(generator);

        String code = "public String normalize(String in) { return in == null ? \"\" : in.trim().toLowerCase(); }";
        PropertyTestSpec spec = generator.generatePropertyTests("StringNormalizer", code);

        assertNotNull(spec);
        assertEquals("StringNormalizer", spec.targetClassName());
        assertFalse(spec.inferredInvariants().isEmpty());
        assertFalse(spec.fuzzInputs().isEmpty());
        assertNotNull(spec.generatedPropertyTestCode());
    }

    @Test
    @DisplayName("Feature 24: Independent Verifier Agent audits code in isolated frame")
    void testIndependentVerifierAgent() {
        IndependentVerifierAgent verifier = IndependentVerifierAgent.getInstance();
        assertNotNull(verifier);

        String req = "Implement high performance thread-safe cache";
        String impl = "public class Cache { private final ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>(); }";
        String test = "@Test void testCache() { assertNotNull(new Cache()); }";

        VerificationAuditResult audit = verifier.verifyImplementation(req, impl, test);
        assertNotNull(audit);
        assertEquals("APPROVED", audit.verdict());
        assertTrue(audit.score() >= 90);
        assertFalse(audit.verifiedRequirements().isEmpty());
    }

    @Test
    @DisplayName("Feature 25: Visual regression engine detects pixel diffs and layout shifts")
    void testVisualRegressionEngine() {
        VisualRegressionEngine engine = VisualRegressionEngine.getInstance();
        assertNotNull(engine);

        BufferedImage imgA = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        BufferedImage imgB = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        // Identical images
        VisualDiffResult diffClean = engine.compareScreenshots("Toolbar", imgA, imgB, 0.05);
        assertNotNull(diffClean);
        assertTrue(diffClean.passed());
        assertEquals(0.0, diffClean.differencePercentage());

        // Modify 10 pixels
        for (int i = 0; i < 10; i++) {
            imgB.setRGB(i, i, 0xFF0000);
        }
        VisualDiffResult diffModified = engine.compareScreenshots("Toolbar", imgA, imgB, 0.01);
        assertNotNull(diffModified);
        assertTrue(diffModified.mismatchedPixels() > 0);
    }

    @Test
    @DisplayName("Feature 26: Runtime behavior replay validates production traces")
    void testRuntimeBehaviorReplay() {
        RuntimeBehaviorReplay replay = RuntimeBehaviorReplay.getInstance();
        assertNotNull(replay);

        assertFalse(replay.getTraces().isEmpty());
        ReplayResult result = replay.replayTraces();
        assertNotNull(result);
        assertTrue(result.replayPassRate() >= 90.0);
        assertEquals(result.totalTracesReplayed(), result.tracesPassed());
    }

    @Test
    @DisplayName("Feature 27: Live Debugger Agent formulates and tests hypotheses")
    void testLiveDebuggerAgent() {
        LiveDebuggerAgent agent = LiveDebuggerAgent.getInstance();
        assertNotNull(agent);

        LiveDebuggerAgent.DebugSessionResult session = agent.runAutonomousDebugging("AuthTokenService.java", "NullPointerException during token parsing");
        assertNotNull(session);
        assertFalse(session.activeBreakpoints().isEmpty());
        assertFalse(session.hypothesesTested().isEmpty());
        assertNotNull(session.winningHypothesis());
        assertNotNull(session.suggestedFixDiff());
    }

    @Test
    @DisplayName("Feature 28: Log-to-root-cause correlates alerts with source locations")
    void testLogToRootCauseAnalyzer() {
        LogToRootCauseAnalyzer analyzer = LogToRootCauseAnalyzer.getInstance();
        assertNotNull(analyzer);

        String rawError = """
                java.lang.NullPointerException: Cannot invoke "String.length()" because "token" is null
                    at com.github.axiomate.agentic.ide.agent.session.SessionManager.switchSession(SessionManager.java:128)
                    at com.github.axiomate.agentic.ide.ui.MainFrame.onSessionSwitched(MainFrame.java:210)
                """;

        RootCauseAnalysis rca = analyzer.analyze(rawError);
        assertNotNull(rca);
        assertEquals("java.lang.NullPointerException", rca.errorType());
        assertTrue(rca.likelyRootCauseFile().contains("SessionManager"));
        assertEquals(128, rca.likelyLineNumber());
        assertNotNull(rca.suggestedRemediation());
    }

    @Test
    @DisplayName("Feature 29: Performance Profiler Agent benchmarks and proves optimization gains")
    void testPerformanceProfilerAgent() {
        PerformanceProfilerAgent profiler = PerformanceProfilerAgent.getInstance();
        assertNotNull(profiler);

        String sampleLoop = """
                public void process(List<Item> listA, List<String> listB) {
                    for (Item a : listA) {
                        if (listB.contains(a.getId())) {
                            doWork(a);
                        }
                    }
                }
                """;

        List<OptimizationProposal> proposals = profiler.profileAndOptimize("BatchProcessor", sampleLoop);
        assertNotNull(proposals);
        assertFalse(proposals.isEmpty());

        OptimizationProposal p = proposals.get(0);
        assertTrue(p.speedupMultiplier() > 1.0);
        assertTrue(p.optimizedLatencyMs() < p.baselineLatencyMs());
        assertNotNull(p.optimizedDiff());
    }

    @Test
    @DisplayName("Feature 30: Reproduction Builder turns bug reports into minimal test cases")
    void testReproductionBuilder() {
        ReproductionBuilder builder = ReproductionBuilder.getInstance();
        assertNotNull(builder);

        ReproductionTestCase repro = builder.buildReproductionTest(
                "Router falls back to default when model is unavailable",
                "When provider returns 429, router should degrade gracefully"
        );

        assertNotNull(repro);
        assertTrue(repro.testClassName().contains("ReproductionTest"));
        assertNotNull(repro.testSourceCode());
        assertNotNull(repro.simulatedPreconditions());
    }
}

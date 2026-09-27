package com.github.axiomate.agentic.ide.features;

import com.github.axiomate.agentic.ide.features.codeunderstanding.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering Code Understanding features (Features 15-20).
 */
public class CodeUnderstandingFeaturesTest {

    @Test
    @DisplayName("Feature 15: Codebase Knowledge Graph tracks modules, callers, and dataflow")
    void testCodebaseKnowledgeGraph() {
        CodebaseKnowledgeGraph graph = CodebaseKnowledgeGraph.getInstance();
        assertNotNull(graph);

        assertFalse(graph.getAllNodes().isEmpty());
        assertFalse(graph.getAllEdges().isEmpty());

        Optional<GraphNode> node = graph.getNode("ui");
        assertTrue(node.isPresent());
        assertEquals("UI Module", node.get().name());

        List<GraphEdge> outgoing = graph.getEdgesFrom("ui");
        assertNotNull(outgoing);
        assertFalse(outgoing.isEmpty());

        // Add custom node
        GraphNode custom = new GraphNode("auth", "Auth Subsystem", "MODULE", "src/auth", "Security Team", java.util.Map.of());
        graph.addNode(custom);
        assertTrue(graph.getNode("auth").isPresent());
    }

    @Test
    @DisplayName("Feature 16: Architecture Drift Detector flags layering violations")
    void testArchitectureDriftDetector() {
        ArchitectureDriftDetector detector = ArchitectureDriftDetector.getInstance();
        assertNotNull(detector);

        assertFalse(detector.getRules().isEmpty());

        // Code violating: UI layer directly instantiating JsonAgentMemoryStore
        String badCode = """
                package com.github.axiomate.agentic.ide.ui;
                import com.github.axiomate.agentic.ide.agent.memory.JsonAgentMemoryStore;
                public class BadUiPanel {
                    void test() {
                        new JsonAgentMemoryStore();
                    }
                }
                """;

        List<DriftViolation> violations = detector.detectDrift("src/main/java/com/github/axiomate/agentic/ide/ui/BadUiPanel.java", badCode);
        assertNotNull(violations);
        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.ruleId().equals("rule-no-ui-direct-memory-store")));

        // Valid code
        String goodCode = """
                package com.github.axiomate.agentic.ide.ui;
                import com.github.axiomate.agentic.ide.agent.AIAgentService;
                public class GoodUiPanel {}
                """;
        List<DriftViolation> clean = detector.detectDrift("src/main/java/com/github/axiomate/agentic/ide/ui/GoodUiPanel.java", goodCode);
        assertTrue(clean.isEmpty());
    }

    @Test
    @DisplayName("Feature 17: 'Explain this repo to me' onboarding tour guide generates walkthrough")
    void testOnboardingTourGuide() {
        OnboardingTourGuide tourGuide = OnboardingTourGuide.getInstance();
        assertNotNull(tourGuide);

        List<TourStop> stops = tourGuide.generateTour(new File("."));
        assertNotNull(stops);
        assertFalse(stops.isEmpty());

        TourStop stop1 = stops.get(0);
        assertEquals(1, stop1.stepNumber());
        assertNotNull(stop1.title());
        assertNotNull(stop1.explanation());
        assertFalse(stop1.keyClassesOrSymbols().isEmpty());
    }

    @Test
    @DisplayName("Feature 18: Historical Context Lens resolves line rationale and commits")
    void testHistoricalContextLens() {
        HistoricalContextLens lens = HistoricalContextLens.getInstance();
        assertNotNull(lens);

        HistoricalContext ctx = lens.resolveHistoricalContext("SessionManager.java", 42, "compressIfExceeded();");
        assertNotNull(ctx);
        assertEquals("AXIOM-88", ctx.ticketId());
        assertEquals("#39", ctx.prNumber());
        assertNotNull(ctx.designRationale());
        assertNotNull(ctx.commitHash());
    }

    @Test
    @DisplayName("Feature 19: Semantic Diff synthesizes behavioral changes")
    void testSemanticDiffEngine() {
        SemanticDiffEngine engine = SemanticDiffEngine.getInstance();
        assertNotNull(engine);

        String patch = """
                @@ -12,4 +12,6 @@
                 void execute() {
                +    if (input == null) throw new IllegalArgumentException();
                +    retries.set(Math.min(5, count));
                 }
                """;

        List<SemanticDiffItem> items = engine.summarizeDiff(patch);
        assertNotNull(items);
        assertFalse(items.isEmpty());
        assertTrue(items.stream().anyMatch(i -> i.changeCategory().equals("DEFENSIVE_CHECK") || i.changeCategory().equals("LOGIC_CHANGE")));
    }

    @Test
    @DisplayName("Feature 20: Hidden Coupling Finder surfaces co-changing files without direct imports")
    void testHiddenCouplingFinder() {
        HiddenCouplingFinder finder = HiddenCouplingFinder.getInstance();
        assertNotNull(finder);

        List<CoupledPair> coupled = finder.findCoupledFiles("src/main/java/com/github/axiomate/agentic/ide/config/IdeConfig.java");
        assertNotNull(coupled);
        assertFalse(coupled.isEmpty());

        CoupledPair pair = coupled.get(0);
        assertTrue(pair.couplingConfidence() > 0.8);
        assertTrue(pair.coChangeCount() > 0);
        assertNotNull(pair.explanation());
    }
}

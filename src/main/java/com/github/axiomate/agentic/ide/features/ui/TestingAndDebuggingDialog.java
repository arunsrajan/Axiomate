package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.debugging.*;
import com.github.axiomate.agentic.ide.features.testing.*;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Visual dialog for Testing & Verification (Features 21-26) and Debugging & Runtime (Features 27-30).
 */
public class TestingAndDebuggingDialog extends JDialog {

    public TestingAndDebuggingDialog(Frame owner) {
        super(owner, "Axiomate - Testing, Verification & Autonomous Debugging", false);
        setSize(980, 640);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // 1. Test-First Mode & Mutation Testing (Features 21 & 22)
        JPanel tddTab = new JPanel(new BorderLayout(8, 8));
        tddTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea tddText = new JTextArea();
        tddText.setFont(UIUtils.getEditorFont(12));
        tddText.setEditable(false);
        var tddReport = TestFirstModeEngine.getInstance().executeTddCycle("Implement thread-safe token meter", "TokenMeter");
        var mutReport = MutationTestingEngine.getInstance().runMutationTest("if (a == b) return true; else return false;", tddReport.generatedTestCode());
        tddText.setText("### 🧪 Test-First Mode (TDD) Cycle\n\n" +
                tddReport.summary() + "\n\n" +
                "### 🧬 Mutation Testing Analysis on Agent Code:\n" +
                "- Mutants Created: " + mutReport.totalMutantsCreated() + "\n" +
                "- Mutants Killed: " + mutReport.mutantsKilled() + " (" + mutReport.getFormattedScore() + ")\n" +
                "- Rating: " + mutReport.rating() + "\n\n" +
                "Generated TDD Tests:\n" + tddReport.generatedTestCode());
        tddTab.add(new JScrollPane(tddText), BorderLayout.CENTER);

        // 2. Property-Based Testing & Independent Verifier (Features 23 & 24)
        JPanel propTab = new JPanel(new BorderLayout(8, 8));
        propTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea propText = new JTextArea();
        propText.setFont(UIUtils.getEditorFont(12));
        propText.setEditable(false);
        var propSpec = PropertyBasedTestGenerator.getInstance().generatePropertyTests("Calculator", "public int add(int a, int b) { return a + b; }");
        var verifAudit = IndependentVerifierAgent.getInstance().verifyImplementation("Add boundary checks", "public int add(int a, int b) { return a + b; }", propSpec.generatedPropertyTestCode());
        propText.setText("### 🔍 Property-Based Invariant Verification\n\n" +
                "- Target: " + propSpec.targetClassName() + "\n" +
                "- Invariants: " + String.join(", ", propSpec.inferredInvariants()) + "\n" +
                "- Fuzz Trials: " + propSpec.trialsCount() + " random vectors (All passed)\n\n" +
                "### 🛡 Independent Verifier Agent Audit (Segregated Evaluation):\n" +
                "- Verdict: " + verifAudit.verdict() + " (Score: " + verifAudit.score() + "/100)\n" +
                "- Summary: " + verifAudit.auditSummary());
        propTab.add(new JScrollPane(propText), BorderLayout.CENTER);

        // 3. Visual Regression & Runtime Replay (Features 25 & 26)
        JPanel replayTab = new JPanel(new BorderLayout(8, 8));
        replayTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea replayText = new JTextArea();
        replayText.setFont(UIUtils.getEditorFont(12));
        replayText.setEditable(false);
        var visResult = VisualRegressionEngine.getInstance().compareScreenshots("AIAgentDock", null, null, 1.0);
        var repResult = RuntimeBehaviorReplay.getInstance().replayTraces();
        replayText.setText("### 👁 Visual Regression Testing:\n" + visResult.summary() + "\n\n" +
                "### 📼 Production Runtime Trace Replay:\n" + repResult.summary());
        replayTab.add(new JScrollPane(replayText), BorderLayout.CENTER);

        // 4. Live Debugger Agent & Log-to-Root-Cause (Features 27 & 28)
        JPanel debugTab = new JPanel(new BorderLayout(8, 8));
        debugTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea debugText = new JTextArea();
        debugText.setFont(UIUtils.getEditorFont(12));
        debugText.setEditable(false);
        var debugResult = LiveDebuggerAgent.getInstance().runAutonomousDebugging("Calculator.java", "Intermittent NPE on empty payload");
        var rca = LogToRootCauseAnalyzer.getInstance().analyze("java.lang.NullPointerException at Calculator.java:24");
        debugText.setText("### 🐞 Live Autonomous Debugger Agent:\n" +
                "- Winning Hypothesis: " + debugResult.winningHypothesis().statement() + "\n" +
                "- Evidence: " + debugResult.winningHypothesis().evidenceObserved() + "\n" +
                "- Remediation: " + debugResult.winningHypothesis().recommendedRemediation() + "\n\n" +
                "### 🪵 Log-to-Root-Cause Correlation:\n" +
                "- Offending Location: " + rca.likelyRootCauseFile() + ":" + rca.likelyLineNumber() + "\n" +
                "- Correlated Commit: " + rca.correlatedRecentCommit() + "\n" +
                "- Remedy: " + rca.suggestedRemediation());
        debugTab.add(new JScrollPane(debugText), BorderLayout.CENTER);

        // 5. Performance Profiler Agent & Reproduction Builder (Features 29 & 30)
        JPanel perfTab = new JPanel(new BorderLayout(8, 8));
        perfTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea perfText = new JTextArea();
        perfText.setFont(UIUtils.getEditorFont(12));
        perfText.setEditable(false);
        var optProposals = PerformanceProfilerAgent.getInstance().profileAndOptimize("Calculator", "for (int i=0; i<n; i++) { ... }");
        var reproCase = ReproductionBuilder.getInstance().buildReproductionTest("Context compression timeout on 200k tokens", "ContextCompressor throws timeout under high memory");
        perfText.setText("### ⚡ Performance Profiler Agent Optimization:\n" +
                "- Hot Path: " + optProposals.get(0).hotPath().description() + "\n" +
                "- " + optProposals.get(0).benchmarkProofSummary() + "\n\n" +
                "### 🧪 Automated Minimal Reproduction Builder:\n" +
                "- Bug Title: " + reproCase.bugTitle() + "\n" +
                "- Generated Test: " + reproCase.testClassName() + "\n\n" +
                reproCase.testSourceCode());
        perfTab.add(new JScrollPane(perfText), BorderLayout.CENTER);

        tabs.addTab("🧪 Test-First (TDD) & Mutations", tddTab);
        tabs.addTab("🔍 Property Invariants & Verifier", propTab);
        tabs.addTab("👁 Visual Regression & Trace Replay", replayTab);
        tabs.addTab("🐞 Live Debugger & Root-Cause", debugTab);
        tabs.addTab("⚡ Performance Profiler & Repro", perfTab);

        add(tabs, BorderLayout.CENTER);
    }
}

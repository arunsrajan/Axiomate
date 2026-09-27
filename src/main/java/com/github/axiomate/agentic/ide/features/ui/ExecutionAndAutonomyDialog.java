package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.execution.*;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Visual dialog for Execution & Autonomy features:
 * Autonomy Dial (Feature 7), Swarm & Arbitration (Feature 8), Speculative Branches (Feature 9),
 * Checkpoints & Time-Travel (Feature 10), Background Jobs (Feature 11), Self-Healing CI (Feature 12),
 * Scheduled Maintenance (Feature 13), and Human-in-the-Loop Breakpoints (Feature 14).
 */
public class ExecutionAndAutonomyDialog extends JDialog {

    public ExecutionAndAutonomyDialog(Frame owner) {
        super(owner, "Axiomate - Execution & Autonomy Controls", false);
        setSize(980, 640);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // 1. Autonomy Dial & Breakpoints (Features 7 & 14)
        JPanel autoTab = new JPanel(new BorderLayout(8, 8));
        autoTab.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel dialTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        dialTop.add(new JLabel("Global Autonomy Dial:"));
        JComboBox<AutonomyLevel> dialCombo = new JComboBox<>(AutonomyLevel.values());
        dialCombo.setSelectedItem(AutonomyDial.getInstance().getGlobalLevel());
        dialCombo.addActionListener(e -> AutonomyDial.getInstance().setGlobalLevel((AutonomyLevel) dialCombo.getSelectedItem()));
        dialTop.add(dialCombo);

        String[] bpCols = {"Rule Name", "Path Pattern", "Action Type", "Enforcement Reason"};
        DefaultTableModel bpModel = new DefaultTableModel(bpCols, 0);
        for (BreakpointRule r : HumanInTheLoopGate.getInstance().getRules()) {
            bpModel.addRow(new Object[]{r.name(), r.pathPattern(), r.actionType(), r.reason()});
        }
        JTable bpTable = new JTable(bpModel);
        bpTable.setFont(new Font("SansSerif", Font.PLAIN, 12));

        autoTab.add(dialTop, BorderLayout.NORTH);
        autoTab.add(new JScrollPane(bpTable), BorderLayout.CENTER);

        // 2. Speculative Branches & Swarm (Features 8 & 9)
        JPanel specTab = new JPanel(new BorderLayout(8, 8));
        specTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea specText = new JTextArea();
        specText.setFont(UIUtils.getEditorFont(12));
        specText.setEditable(false);
        var specResult = SpeculativeBranchManager.getInstance().evaluateSpeculativeBranches("Optimize list filter pipeline", "");
        specText.setText(specResult.comparisonSummary());
        specTab.add(new JScrollPane(specText), BorderLayout.CENTER);

        // 3. Time-Travel Checkpoints (Feature 10)
        JPanel timeTab = new JPanel(new BorderLayout(8, 8));
        timeTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] cpCols = {"Step #", "Checkpoint ID", "Description", "Timestamp"};
        DefaultTableModel cpModel = new DefaultTableModel(cpCols, 0);
        List<AgentCheckpoint> cps = CheckpointTimeTravelManager.getInstance().getCheckpoints("session-default");
        if (cps.isEmpty()) {
            CheckpointTimeTravelManager.getInstance().createCheckpoint("session-default", "Initial Baseline", java.util.Map.of(), "Calculator.java");
            cps = CheckpointTimeTravelManager.getInstance().getCheckpoints("session-default");
        }
        for (AgentCheckpoint c : cps) {
            cpModel.addRow(new Object[]{c.stepNumber(), c.checkpointId(), c.stepDescription(), c.timestamp().toString()});
        }
        JTable cpTable = new JTable(cpModel);
        cpTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        timeTab.add(new JScrollPane(cpTable), BorderLayout.CENTER);

        // 4. Background Jobs & Scheduled Maintenance (Features 11 & 13)
        JPanel jobTab = new JPanel(new BorderLayout(8, 8));
        jobTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] jobCols = {"Task Name", "Type", "Recurrence Schedule", "Status", "Last Run Summary"};
        DefaultTableModel jobModel = new DefaultTableModel(jobCols, 0);
        for (MaintenanceTask t : ScheduledMaintenanceManager.getInstance().getTasks()) {
            jobModel.addRow(new Object[]{t.getName(), t.getType(), t.getCronOrInterval(), t.getLastRunStatus(), t.getLastReportSummary()});
        }
        JTable jobTable = new JTable(jobModel);
        jobTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        jobTab.add(new JScrollPane(jobTable), BorderLayout.CENTER);

        // 5. Self-Healing CI (Feature 12)
        JPanel ciTab = new JPanel(new BorderLayout(8, 8));
        ciTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea ciText = new JTextArea();
        ciText.setFont(UIUtils.getEditorFont(12));
        ciText.setEditable(false);
        String sampleCiLog = "[ERROR] /src/main/java/com/example/Calculator.java:[42,18] NullPointerException during unboxing\n[ERROR] Tests run: 5, Failures: 1, Errors: 0";
        var diag = SelfHealingCiService.getInstance().diagnoseAndHeal(sampleCiLog);
        ciText.setText("### 🤖 Self-Healing CI Pipeline Diagnosis\n\n" +
                "- Failed Tool: " + diag.buildTool() + "\n" +
                "- Target File: " + diag.targetFile() + ":" + diag.targetLineNumber() + "\n" +
                "- Root Cause: " + diag.rootCauseSummary() + "\n\n" +
                "### Proposed Automated Fix Patch:\n" + diag.proposedFix().proposedDiff() + "\n\n" +
                "### Automated Pull Request Body:\n" + diag.proposedFix().generatedPrBody());
        ciTab.add(new JScrollPane(ciText), BorderLayout.CENTER);

        tabs.addTab("🎛 Autonomy Dial & Breakpoints", autoTab);
        tabs.addTab("🌿 Speculative Branches & Swarm", specTab);
        tabs.addTab("⏳ Time-Travel & Checkpoints", timeTab);
        tabs.addTab("🔄 Scheduled Maintenance & Jobs", jobTab);
        tabs.addTab("🩹 Self-Healing CI", ciTab);

        add(tabs, BorderLayout.CENTER);
    }
}

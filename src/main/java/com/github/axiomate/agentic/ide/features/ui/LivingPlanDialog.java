package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.planning.*;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Interactive Dialog for Living Plan Canvas (Feature 2), Intent Clarification (Feature 1),
 * Blast Radius Preview (Feature 3), Effort & Cost Estimator (Feature 4), Spec Decomposition (Feature 5),
 * and Approach Rationale (Feature 6).
 */
public class LivingPlanDialog extends JDialog {

    private static final Logger log = LoggerFactory.getLogger(LivingPlanDialog.class);

    private final LivingPlanCanvas canvas = LivingPlanCanvas.getInstance();
    private final DefaultTableModel planTableModel;
    private final JTable planTable;
    private final JTextArea blastRadiusText;
    private final JTextArea effortEstimateText;
    private final JTextArea rationaleText;
    private final JPanel clarificationPanel;

    public LivingPlanDialog(Frame owner) {
        super(owner, "Axiomate - Living Plan Canvas & Architectural Preview", false);
        setSize(950, 650);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // 1. Living Plan Canvas Tab
        JPanel planTab = new JPanel(new BorderLayout(8, 8));
        planTab.setBorder(new EmptyBorder(10, 10, 10, 10));

        String[] cols = {"#", "Status", "Step Title", "Affected Files", "Risks"};
        planTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        planTable = new JTable(planTableModel);
        planTable.setRowHeight(26);
        planTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        planTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        planTable.getColumnModel().getColumn(0).setPreferredWidth(35);
        planTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        planTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        planTable.getColumnModel().getColumn(3).setPreferredWidth(160);
        planTable.getColumnModel().getColumn(4).setPreferredWidth(180);

        JScrollPane planScroll = new JScrollPane(planTable);

        // Control buttons
        JPanel planControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton moveUpBtn = new JButton("▲ Move Up");
        JButton moveDownBtn = new JButton("▼ Move Down");
        JButton markDoneBtn = new JButton("✔ Mark Done");
        JButton addStepBtn = new JButton("+ Add Step");
        JButton refreshBtn = new JButton("🔄 Refresh");

        moveUpBtn.addActionListener(e -> {
            int row = planTable.getSelectedRow();
            if (row > 0) {
                canvas.moveTaskUp(row);
                refreshPlanTable();
                planTable.setRowSelectionInterval(row - 1, row - 1);
            }
        });

        moveDownBtn.addActionListener(e -> {
            int row = planTable.getSelectedRow();
            if (row >= 0 && row < planTableModel.getRowCount() - 1) {
                canvas.moveTaskDown(row);
                refreshPlanTable();
                planTable.setRowSelectionInterval(row + 1, row + 1);
            }
        });

        markDoneBtn.addActionListener(e -> {
            int row = planTable.getSelectedRow();
            if (row >= 0 && row < canvas.getTasks().size()) {
                PlanTask task = canvas.getTasks().get(row);
                canvas.updateTaskStatus(task.getId(), PlanStatus.COMPLETED);
                refreshPlanTable();
            }
        });

        addStepBtn.addActionListener(e -> {
            String title = JOptionPane.showInputDialog(this, "Enter step title:", "Add Plan Step", JOptionPane.PLAIN_MESSAGE);
            if (title != null && !title.isBlank()) {
                canvas.addTask(new PlanTask("step-" + System.currentTimeMillis(), title.trim(), "User defined step", List.of("src/App.java"), List.of(), canvas.getTasks().size()));
                refreshPlanTable();
            }
        });

        refreshBtn.addActionListener(e -> refreshPlanTable());

        planControls.add(moveUpBtn);
        planControls.add(moveDownBtn);
        planControls.add(markDoneBtn);
        planControls.add(addStepBtn);
        planControls.add(refreshBtn);

        planTab.add(planScroll, BorderLayout.CENTER);
        planTab.add(planControls, BorderLayout.SOUTH);

        // 2. Blast-Radius Preview Tab
        JPanel blastTab = new JPanel(new BorderLayout(8, 8));
        blastTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        blastRadiusText = new JTextArea();
        blastRadiusText.setEditable(false);
        blastRadiusText.setFont(UIUtils.getEditorFont(13));
        blastRadiusText.setText(getBlastRadiusPreviewText());
        blastTab.add(new JScrollPane(blastRadiusText), BorderLayout.CENTER);

        // 3. Effort & Cost Estimator Tab
        JPanel effortTab = new JPanel(new BorderLayout(8, 8));
        effortTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        effortEstimateText = new JTextArea();
        effortEstimateText.setEditable(false);
        effortEstimateText.setFont(UIUtils.getEditorFont(13));
        effortEstimateText.setText(getEffortEstimateText());
        effortTab.add(new JScrollPane(effortEstimateText), BorderLayout.CENTER);

        // 4. "Why this approach" Rationale Tab
        JPanel rationaleTab = new JPanel(new BorderLayout(8, 8));
        rationaleTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        rationaleText = new JTextArea();
        rationaleText.setEditable(false);
        rationaleText.setFont(UIUtils.getEditorFont(13));
        rationaleText.setText(getApproachRationaleText());
        rationaleTab.add(new JScrollPane(rationaleText), BorderLayout.CENTER);

        // 5. Intent Clarification Tab
        JPanel clarifTab = new JPanel(new BorderLayout(8, 8));
        clarifTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        clarificationPanel = new JPanel();
        clarificationPanel.setLayout(new BoxLayout(clarificationPanel, BoxLayout.Y_AXIS));
        populateClarificationPanel();
        clarifTab.add(new JScrollPane(clarificationPanel), BorderLayout.CENTER);

        tabs.addTab("📋 Living Plan Canvas", planTab);
        tabs.addTab("💥 Blast-Radius Preview", blastTab);
        tabs.addTab("⏱ Effort & Cost Estimator", effortTab);
        tabs.addTab("💡 Approach Rationale", rationaleTab);
        tabs.addTab("❓ Intent Clarification", clarifTab);

        add(tabs, BorderLayout.CENTER);

        // Listen for external plan changes
        canvas.addListener(this::refreshPlanTable);
        refreshPlanTable();
    }

    private void refreshPlanTable() {
        planTableModel.setRowCount(0);
        List<PlanTask> tasks = canvas.getTasks();
        for (int i = 0; i < tasks.size(); i++) {
            PlanTask t = tasks.get(i);
            planTableModel.addRow(new Object[]{
                    (i + 1),
                    t.getStatus().getDisplayLabel(),
                    t.getTitle(),
                    String.join(", ", t.getTargetFiles()),
                    String.join(", ", t.getIdentifiedRisks())
            });
        }
    }

    private String getBlastRadiusPreviewText() {
        BlastRadiusResult res = BlastRadiusAnalyzer.getInstance().analyze("Calculator.java", "Refactor and optimize");
        StringBuilder sb = new StringBuilder();
        sb.append("### 💥 Pre-flight Blast Radius Impact Analysis\n\n");
        sb.append("- Target File: ").append(res.targetFile()).append("\n");
        sb.append("- Blast Radius Score: ").append(res.impactScore()).append("/100 (").append(res.riskLevel()).append(" RISK)\n");
        sb.append("- Direct Files Modified: ").append(String.join(", ", res.directlyModifiedFiles())).append("\n");
        sb.append("- Indirect Dependent Files: ").append(res.indirectlyAffectedFiles().isEmpty() ? "None detected" : String.join(", ", res.indirectlyAffectedFiles())).append("\n");
        sb.append("- Test Suites Impacted: ").append(String.join(", ", res.affectedTests())).append("\n");
        sb.append("- Downstream Exposed APIs: ").append(String.join(", ", res.affectedApis())).append("\n");
        sb.append("- Downstream Services: ").append(String.join(", ", res.downstreamServices())).append("\n\n");
        sb.append("Rationale:\n").append(res.rationale());
        return sb.toString();
    }

    private String getEffortEstimateText() {
        EffortEstimate est = EffortCostEstimator.getInstance().estimate("Refactor core architecture with tests", "", "ANTHROPIC");
        StringBuilder sb = new StringBuilder();
        sb.append("### ⏱ Predictive Effort & Token Cost Estimator\n\n");
        sb.append("- Predicted Duration: ").append(est.getFormattedDuration()).append("\n");
        sb.append("- Complexity Rating: ").append(est.complexityRating()).append("\n");
        sb.append("- Confidence Score: ").append(String.format("%.1f%%", est.confidenceScore() * 100.0)).append("\n");
        sb.append("- Estimated Input Tokens: ").append(est.estimatedInputTokens()).append("\n");
        sb.append("- Estimated Output Tokens: ").append(est.estimatedOutputTokens()).append("\n");
        sb.append("- Estimated Total Tokens: ").append(est.estimatedTotalTokens()).append("\n\n");
        sb.append("### 💵 Estimated Cost Across AI Providers:\n");
        for (var entry : est.costByProvider().entrySet()) {
            sb.append(String.format("  - %-12s: $%.5f\n", entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    private String getApproachRationaleText() {
        ApproachRationale rat = ApproachRationaleEngine.getInstance().generateRationale("Refactor concurrency", "Calculator.java");
        StringBuilder sb = new StringBuilder();
        sb.append("### 🏆 Chosen Architectural Approach: ").append(rat.chosenApproachTitle()).append("\n\n");
        sb.append(rat.chosenApproachDescription()).append("\n\n");
        sb.append("**Benefits:**\n");
        for (String b : rat.chosenApproachBenefits()) sb.append("- ").append(b).append("\n");

        sb.append("\n### ❌ Rejected Architectural Alternatives & Why Each Lost:\n\n");
        for (var alt : rat.rejectedAlternatives()) {
            sb.append("#### ").append(alt.alternativeTitle()).append("\n");
            sb.append("- Summary: ").append(alt.summary()).append("\n");
            sb.append("- Why Rejected: ").append(alt.whyRejectedRationale()).append("\n");
            sb.append("- Deficit: ").append(alt.tradeOffDeficit()).append("\n\n");
        }
        return sb.toString();
    }

    private void populateClarificationPanel() {
        clarificationPanel.removeAll();
        List<ClarificationQuestion> questions = IntentClarificationService.getInstance().analyzeIntent("Refactor database auth service", "AuthService.java");

        JLabel header = new JLabel("Intent Clarification Loop (Resolve Assumptions in One Pass)");
        header.setFont(new Font("SansSerif", Font.BOLD, 13));
        header.setBorder(new EmptyBorder(4, 4, 10, 4));
        clarificationPanel.add(header);

        for (ClarificationQuestion q : questions) {
            JPanel card = new JPanel(new BorderLayout(6, 4));
            card.setBorder(BorderFactory.createTitledBorder(q.category()));
            JLabel qLabel = new JLabel("<html><b>" + q.question() + "</b><br><i>Assumed Default: " + q.assumedDefault() + "</i></html>");
            JComboBox<String> combo = new JComboBox<>(q.options().toArray(new String[0]));
            combo.setSelectedItem(q.assumedDefault());

            card.add(qLabel, BorderLayout.CENTER);
            card.add(combo, BorderLayout.SOUTH);
            clarificationPanel.add(card);
            clarificationPanel.add(Box.createVerticalStrut(8));
        }
    }
}

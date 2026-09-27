package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.collaboration.*;
import com.github.axiomate.agentic.ide.features.devexperience.*;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Visual dialog for Collaboration and Developer Experience features:
 * Multiplayer sessions (Feature 37), Handoff Briefs (Feature 38), PR Review Copilot (Feature 39),
 * Team Conventions (Feature 40), Stakeholder Summaries (Feature 41), Voice/Sketch (Feature 46),
 * Confidence Heatmap (Feature 47), Learning Mode (Feature 48), and Focus Guardian (Feature 49).
 */
public class CollaborativeAndDevExDialog extends JDialog {

    public CollaborativeAndDevExDialog(Frame owner) {
        super(owner, "Axiomate - Collaboration & Developer Experience", false);
        setSize(980, 640);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.PLAIN, 12));

        // 1. Multiplayer Sessions Tab (Feature 37)
        JPanel multiTab = new JPanel(new BorderLayout(8, 8));
        multiTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String[] peerCols = {"Peer Name", "Cursor / File", "Active Steering Intent", "Color"};
        DefaultTableModel peerModel = new DefaultTableModel(peerCols, 0);
        for (CollaborativePeer p : MultiplayerSessionManager.getInstance().getActivePeers()) {
            peerModel.addRow(new Object[]{p.displayName(), p.cursorPosition(), p.activeIntent(), p.colorHex()});
        }
        JTable peerTable = new JTable(peerModel);
        peerTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        multiTab.add(new JScrollPane(peerTable), BorderLayout.CENTER);

        // 2. Confidence Heatmap Tab (Feature 47)
        JPanel heatmapTab = new JPanel(new BorderLayout(8, 8));
        heatmapTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        String sampleCode = """
                public class Calculator {
                    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();
                    public int compute(int a, int b) {
                        if (a < 0 || b < 0) throw new IllegalArgumentException();
                        return a + b;
                    }
                }
                """;
        HeatmapReport report = ConfidenceHeatmapService.getInstance().generateHeatmap("Calculator.java", sampleCode);

        String[] heatCols = {"Line #", "Certainty", "Level", "Code Line", "Review Scrutiny Note"};
        DefaultTableModel heatModel = new DefaultTableModel(heatCols, 0);
        for (HeatmapLine l : report.lines()) {
            heatModel.addRow(new Object[]{
                    l.lineNumber(), String.format("%.0f%%", l.confidenceScore() * 100.0),
                    l.confidenceLevel(), l.lineContent(), l.scrutinyReason() != null ? l.scrutinyReason() : "-"
            });
        }
        JTable heatTable = new JTable(heatModel);
        heatTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        heatmapTab.add(new JScrollPane(heatTable), BorderLayout.CENTER);

        // 3. Learning Mode Tab (Feature 48)
        JPanel learnTab = new JPanel(new BorderLayout(8, 8));
        learnTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea learnText = new JTextArea();
        learnText.setFont(UIUtils.getEditorFont(12));
        learnText.setEditable(false);
        LearningLesson lesson = LearningModeEngine.getInstance().createLesson("Converted to immutable Java 21 record", sampleCode);
        learnText.setText(lesson.toMarkdown());
        learnTab.add(new JScrollPane(learnText), BorderLayout.CENTER);

        // 4. Focus Guardian Tab (Feature 49)
        JPanel focusTab = new JPanel(new BorderLayout(8, 8));
        focusTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        FocusGuardianService fg = FocusGuardianService.getInstance();
        JCheckBox focusToggle = new JCheckBox("Focus Mode Active (Batching non-urgent queries)", fg.isFocusModeActive());
        focusToggle.addActionListener(e -> fg.setFocusModeActive(focusToggle.isSelected()));

        String[] focusCols = {"Category", "Title", "Priority", "Queued Time"};
        DefaultTableModel focusModel = new DefaultTableModel(focusCols, 0);
        for (QueuedNotification qn : fg.getQueuedNotifications()) {
            focusModel.addRow(new Object[]{qn.category(), qn.title(), qn.priority(), qn.queuedAt().toString().substring(11, 19)});
        }
        JTable focusTable = new JTable(focusModel);
        focusTable.setFont(new Font("SansSerif", Font.PLAIN, 12));

        JButton flushBtn = new JButton("⚡ Flush & Release All Batched Notifications");
        flushBtn.addActionListener(e -> {
            List<QueuedNotification> released = fg.flushBatch();
            focusModel.setRowCount(0);
            JOptionPane.showMessageDialog(this, "Released " + released.size() + " queued notifications to the developer.", "Batch Released", JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel focusTop = new JPanel(new BorderLayout());
        focusTop.add(focusToggle, BorderLayout.WEST);
        focusTop.add(flushBtn, BorderLayout.EAST);

        focusTab.add(focusTop, BorderLayout.NORTH);
        focusTab.add(new JScrollPane(focusTable), BorderLayout.CENTER);

        // 5. Handoff Brief & PR Copilot Tab (Features 38 & 39)
        JPanel handoffTab = new JPanel(new BorderLayout(8, 8));
        handoffTab.setBorder(new EmptyBorder(10, 10, 10, 10));
        JTextArea handoffText = new JTextArea();
        handoffText.setFont(UIUtils.getEditorFont(12));
        handoffText.setEditable(false);
        HandoffBrief brief = AgentHandoffNotesService.getInstance().generateHandoffBrief(
                "Modernize Concurrency Architecture", "Alice", "Bob", List.of("Calculator.java", "IdeConfig.java"), "Completed first pass"
        );
        handoffText.setText(brief.toMarkdown());
        handoffTab.add(new JScrollPane(handoffText), BorderLayout.CENTER);

        tabs.addTab("👥 Multiplayer Sessions", multiTab);
        tabs.addTab("🌡 Confidence Heatmap", heatmapTab);
        tabs.addTab("🎓 Learning Mode", learnTab);
        tabs.addTab("🛡 Focus Guardian", focusTab);
        tabs.addTab("🤝 Agent Handoff Brief", handoffTab);

        add(tabs, BorderLayout.CENTER);
    }
}

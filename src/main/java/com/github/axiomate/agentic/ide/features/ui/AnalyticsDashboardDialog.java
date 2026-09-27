package com.github.axiomate.agentic.ide.features.ui;

import com.github.axiomate.agentic.ide.features.devexperience.AgentAnalyticsDashboard;
import com.github.axiomate.agentic.ide.features.devexperience.AgentMetrics;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/**
 * Visual dialog for Feature 50: Agent analytics dashboard.
 * Tracks acceptance rate, rework, time saved, cost, and failure patterns per task type.
 */
public class AnalyticsDashboardDialog extends JDialog {

    public AnalyticsDashboardDialog(Frame owner) {
        super(owner, "Axiomate - Agent Analytics & Productivity Dashboard", false);
        setSize(850, 580);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));

        AgentMetrics metrics = AgentAnalyticsDashboard.getInstance().computeMetrics();

        // 1. KPI Metric Cards Header
        JPanel kpiPanel = new JPanel(new GridLayout(1, 4, 10, 0));
        kpiPanel.setBorder(new EmptyBorder(12, 12, 8, 12));

        kpiPanel.add(createCard("Acceptance Rate", metrics.getFormattedAcceptanceRate(), UIUtils.SUCCESS_COLOR));
        kpiPanel.add(createCard("Rework Rate", metrics.getFormattedReworkRate(), UIUtils.WARNING_COLOR));
        kpiPanel.add(createCard("Time Saved", metrics.getFormattedHoursSaved(), UIUtils.ACCENT_COLOR));
        kpiPanel.add(createCard("Dollar Savings", metrics.getFormattedDollarSavings(), UIUtils.ACCENT_PURPLE));

        add(kpiPanel, BorderLayout.NORTH);

        // 2. Central Details Panel (Failure Patterns & Latencies)
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        centerPanel.setBorder(new EmptyBorder(0, 12, 12, 12));

        // Failure patterns table
        JPanel failurePanel = new JPanel(new BorderLayout(6, 6));
        failurePanel.setBorder(BorderFactory.createTitledBorder("Failure Patterns & Rework Distribution"));
        String[] failCols = {"Task Type & Failure Pattern", "Occurrences"};
        DefaultTableModel failModel = new DefaultTableModel(failCols, 0);
        for (Map.Entry<String, Integer> e : metrics.failurePatternsPerTaskType().entrySet()) {
            failModel.addRow(new Object[]{e.getKey(), e.getValue()});
        }
        JTable failTable = new JTable(failModel);
        failTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        failurePanel.add(new JScrollPane(failTable), BorderLayout.CENTER);

        // Model Latency table
        JPanel latencyPanel = new JPanel(new BorderLayout(6, 6));
        latencyPanel.setBorder(BorderFactory.createTitledBorder("Model Latency & Throughput Benchmarks"));
        String[] latCols = {"Model Name", "Average Latency"};
        DefaultTableModel latModel = new DefaultTableModel(latCols, 0);
        for (Map.Entry<String, Double> e : metrics.latencyPerModelMs().entrySet()) {
            latModel.addRow(new Object[]{e.getKey(), String.format("%.1f ms", e.getValue())});
        }
        JTable latTable = new JTable(latModel);
        latTable.setFont(new Font("SansSerif", Font.PLAIN, 12));
        latencyPanel.add(new JScrollPane(latTable), BorderLayout.CENTER);

        centerPanel.add(failurePanel);
        centerPanel.add(latencyPanel);

        add(centerPanel, BorderLayout.CENTER);

        // Footer Summary
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        JLabel summaryLabel = new JLabel(String.format("Total Tasks: %d | Direct Accepted: %d | Total Spent: $%.3f",
                metrics.totalTasksExecuted(), metrics.tasksAcceptedDirectly(), metrics.totalCostUsd()));
        summaryLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        summaryLabel.setForeground(Color.GRAY);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        footer.add(summaryLabel);
        footer.add(closeBtn);

        add(footer, BorderLayout.SOUTH);
    }

    private JPanel createCard(String title, String value, Color accent) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBorder(new CompoundBorder(new LineBorder(new Color(60, 60, 65), 1), new EmptyBorder(10, 12, 10, 12)));
        card.setBackground(new Color(30, 32, 38));

        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 11));
        titleLbl.setForeground(Color.LIGHT_GRAY);

        JLabel valLbl = new JLabel(value);
        valLbl.setFont(new Font("SansSerif", Font.BOLD, 22));
        valLbl.setForeground(accent);

        card.add(titleLbl, BorderLayout.NORTH);
        card.add(valLbl, BorderLayout.CENTER);
        return card;
    }
}

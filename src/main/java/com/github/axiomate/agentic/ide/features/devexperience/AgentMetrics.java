package com.github.axiomate.agentic.ide.features.devexperience;

import java.util.Map;

/**
 * Metric snapshot for agent productivity and reliability analytics.
 */
public record AgentMetrics(
        int totalTasksExecuted,
        int tasksAcceptedDirectly,
        int tasksReworked,
        double acceptanceRatePercent, // (accepted / total) * 100
        double reworkRatePercent,     // (reworked / total) * 100
        double estimatedHoursSaved,
        double totalCostUsd,
        double estimatedDollarSavings, // (dev hours saved * standard dev hourly rate) - cost
        Map<String, Integer> failurePatternsPerTaskType,
        Map<String, Double> latencyPerModelMs
) {
    public String getFormattedAcceptanceRate() {
        return String.format("%.1f%%", acceptanceRatePercent);
    }

    public String getFormattedReworkRate() {
        return String.format("%.1f%%", reworkRatePercent);
    }

    public String getFormattedHoursSaved() {
        return String.format("%.1f hrs", estimatedHoursSaved);
    }

    public String getFormattedDollarSavings() {
        return String.format("$%.2f", estimatedDollarSavings);
    }
}

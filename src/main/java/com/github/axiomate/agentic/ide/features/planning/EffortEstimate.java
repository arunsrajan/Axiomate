package com.github.axiomate.agentic.ide.features.planning;

import java.util.Map;

/**
 * Result model for Effort and Cost Estimation.
 */
public record EffortEstimate(
        int estimatedDurationSeconds,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        int estimatedTotalTokens,
        double estimatedCostUsd,
        Map<String, Double> costByProvider,
        double confidenceScore, // 0.0 - 1.0 (e.g. 0.92 = 92% confidence)
        String complexityRating // LOW, MEDIUM, HIGH, COMPLEX
) {
    public String getFormattedDuration() {
        if (estimatedDurationSeconds < 60) {
            return estimatedDurationSeconds + "s";
        }
        int mins = estimatedDurationSeconds / 60;
        int secs = estimatedDurationSeconds % 60;
        return mins + "m " + secs + "s";
    }

    public String getFormattedCost() {
        return String.format("$%.4f", estimatedCostUsd);
    }
}

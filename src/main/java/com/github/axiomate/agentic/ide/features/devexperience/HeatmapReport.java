package com.github.axiomate.agentic.ide.features.devexperience;

import java.util.List;

/**
 * Report model containing line-by-line confidence heatmap annotations for review.
 */
public record HeatmapReport(
        String targetFile,
        int totalLines,
        double averageConfidence,
        int highConfidenceCount,
        int mediumConfidenceCount,
        int lowConfidenceCount,
        List<HeatmapLine> lines,
        List<String> suggestedReviewFocalPoints
) {
    public String getFormattedAvgConfidence() {
        return String.format("%.1f%%", averageConfidence * 100.0);
    }
}

package com.github.axiomate.agentic.ide.features.devexperience;

/**
 * Model representing confidence scoring for an individual line of agent-written code.
 */
public record HeatmapLine(
        int lineNumber,
        String lineContent,
        double confidenceScore, // 0.0 - 1.0 (e.g. 0.95 = 95% certain)
        String confidenceLevel, // HIGH (>= 0.85, Green), MEDIUM (0.65 - 0.84, Yellow), LOW (< 0.65, Red/Orange)
        String colorHex,        // e.g. "#238636" (green), "#D29922" (yellow), "#DA3633" (red)
        String scrutinyReason   // Why reviewers should look closely if confidence is low
) {
}

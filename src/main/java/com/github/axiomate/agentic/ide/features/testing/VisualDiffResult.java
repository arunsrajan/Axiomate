package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;

/**
 * Result model representing pixel and layout diffs in visual regression testing.
 */
public record VisualDiffResult(
        String componentName,
        int totalPixelsCompared,
        int mismatchedPixels,
        double differencePercentage, // 0.0 - 100.0%
        boolean passed,              // true if diff < tolerance threshold
        List<String> layoutShifts,
        String diffArtifactPath,
        String summary
) {
    public String getFormattedDiff() {
        return String.format("%.2f%%", differencePercentage);
    }
}

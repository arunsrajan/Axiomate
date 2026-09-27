package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;

/**
 * Result model for trace replay execution.
 */
public record ReplayResult(
        int totalTracesReplayed,
        int tracesPassed,
        int regressionsDetected,
        double replayPassRate, // (passed / total) * 100
        List<String> regressionDescriptions,
        String summary
) {
}

package com.github.axiomate.agentic.ide.features.debugging;

import java.util.List;

/**
 * Model representing correlated root-cause analysis from error logs.
 */
public record RootCauseAnalysis(
        String errorType,
        String errorMessage,
        String topStackTraceFrame,
        String likelyRootCauseFile,
        int likelyLineNumber,
        String correlatedRecentCommit,
        String explanation,
        String suggestedRemediation
) {
}

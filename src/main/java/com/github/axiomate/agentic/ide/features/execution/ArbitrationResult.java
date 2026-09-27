package com.github.axiomate.agentic.ide.features.execution;

import java.util.List;
import java.util.Map;

/**
 * Result produced by the Reviewer/Arbitrator Agent after reconciling swarm outputs.
 */
public record ArbitrationResult(
        boolean hasConflicts,
        List<String> reconciledConflictFiles,
        Map<String, String> unifiedChangeset, // filePath -> reconciledContent
        List<String> arbitrationNotes,
        String summary
) {
}

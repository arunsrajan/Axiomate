package com.github.axiomate.agentic.ide.features.debugging;

/**
 * Model representing an optimization proposal with proven benchmark gains.
 */
public record OptimizationProposal(
        ProfileHotPath hotPath,
        String proposedOptimizationTitle,
        String optimizedDiff,
        double baselineLatencyMs,
        double optimizedLatencyMs,
        double speedupMultiplier, // e.g. 4.2x
        long memorySavedBytes,
        String benchmarkProofSummary
) {
}

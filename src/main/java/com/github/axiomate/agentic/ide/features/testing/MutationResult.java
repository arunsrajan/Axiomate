package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;

/**
 * Result of running mutation analysis on agent-generated code.
 */
public record MutationResult(
        int totalMutantsCreated,
        int mutantsKilled,
        int mutantsSurvived,
        double mutationScorePercent, // (killed / total) * 100
        List<Mutant> mutants,
        String rating // STRONG, MODERATE, WEAK
) {
    public String getFormattedScore() {
        return String.format("%.1f%%", mutationScorePercent);
    }
}

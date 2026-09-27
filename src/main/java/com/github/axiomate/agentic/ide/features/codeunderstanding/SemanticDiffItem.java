package com.github.axiomate.agentic.ide.features.codeunderstanding;

/**
 * A discrete semantic behavior change represented in a diff.
 */
public record SemanticDiffItem(
        String changeCategory, // LOGIC_CHANGE, DEFENSIVE_CHECK, PERFORMANCE, API_CHANGE, REFACTORING
        String affectedSymbolOrMethod,
        String behavioralSummary,
        String beforeBehavior,
        String afterBehavior,
        String impactRating // MINOR, MODERATE, MAJOR
) {
}

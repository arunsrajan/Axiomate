package com.github.axiomate.agentic.ide.features.collaboration;

import java.time.Instant;

/**
 * Model representing a learned team convention, style rule, or anti-pattern.
 */
public record TeamConvention(
        String conventionId,
        String category, // "NAMING", "ARCHITECTURE", "TESTING", "STYLE", "ANTI_PATTERN"
        String ruleDescription,
        String positiveExample,
        String negativeExample,
        int confidenceScore, // e.g. 95
        Instant learnedAt,
        String sourcePrOrReview
) {
}

package com.github.axiomate.agentic.ide.features.planning;

import java.util.List;

/**
 * Data model for selected architectural approach and rejected alternatives.
 */
public record ApproachRationale(
        String chosenApproachTitle,
        String chosenApproachDescription,
        List<String> chosenApproachBenefits,
        List<RejectedAlternative> rejectedAlternatives
) {
    public record RejectedAlternative(
            String alternativeTitle,
            String summary,
            String whyRejectedRationale,
            String tradeOffDeficit
    ) {}
}

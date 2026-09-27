package com.github.axiomate.agentic.ide.features.codeunderstanding;

import java.util.List;

/**
 * An individual milestone in the repository onboarding walkthrough tour.
 */
public record TourStop(
        int stepNumber,
        String title,
        String keyFileOrDirectory,
        String architecturalRole,
        String explanation,
        List<String> keyClassesOrSymbols,
        String recommendedNextAction
) {
}

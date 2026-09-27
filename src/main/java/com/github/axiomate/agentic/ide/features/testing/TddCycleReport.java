package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;

/**
 * Report produced by a Test-First (TDD) execution cycle.
 */
public record TddCycleReport(
        String phase, // RED_TESTS_WRITTEN, GREEN_CODE_IMPLEMENTED, REFACTORED
        String generatedTestCode,
        String generatedImplementationCode,
        boolean initialTestsFailedAsExpected,
        boolean finalTestsPassed,
        int totalTestCases,
        List<String> verifiedBehaviors,
        String summary
) {
}

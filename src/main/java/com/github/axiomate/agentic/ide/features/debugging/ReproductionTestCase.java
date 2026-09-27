package com.github.axiomate.agentic.ide.features.debugging;

import java.util.List;

/**
 * Model representing a minimal, self-contained reproducible test case generated from a bug report.
 */
public record ReproductionTestCase(
        String bugTitle,
        String targetComponent,
        String testClassName,
        String testSourceCode,
        List<String> simulatedPreconditions,
        String expectedFailureAssertion,
        String isolationNotes
) {
}

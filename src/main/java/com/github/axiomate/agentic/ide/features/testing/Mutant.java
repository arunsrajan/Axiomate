package com.github.axiomate.agentic.ide.features.testing;

/**
 * An individual synthesized code mutation (fault injection).
 */
public record Mutant(
        String mutantId,
        String mutationOperator, // e.g. "INVERT_CONDITION", "BOUNDARY_MUTATION", "RETURN_VALUE_SUBSTITUTION"
        int lineNumber,
        String originalLine,
        String mutatedLine,
        boolean killed, // true if tests caught the bug, false if mutant survived
        String killingTest
) {
}

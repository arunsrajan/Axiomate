package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;

/**
 * Model representing synthesized invariant properties and generated fuzz tests.
 */
public record PropertyTestSpec(
        String targetClassName,
        List<String> inferredInvariants,
        List<String> fuzzInputs,
        String generatedPropertyTestCode,
        int trialsCount,
        boolean allInvariantsHold
) {
}

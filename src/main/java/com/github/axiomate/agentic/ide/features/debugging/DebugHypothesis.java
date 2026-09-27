package com.github.axiomate.agentic.ide.features.debugging;

import java.util.List;

/**
 * Diagnostic hypothesis formulated and tested autonomously by the Live Debugger Agent.
 */
public record DebugHypothesis(
        String hypothesisId,
        String statement,
        String proposedProbeOrCheck,
        boolean confirmed,
        String evidenceObserved,
        String recommendedRemediation
) {
}

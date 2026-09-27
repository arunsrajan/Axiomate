package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;
import java.util.Map;

/**
 * Captured production or integration runtime execution trace.
 */
public record ExecutionTrace(
        String traceId,
        String endpointOrMethod,
        Map<String, String> inputParameters,
        Map<String, String> mockedDependencies,
        String expectedOutput,
        long originalLatencyMs
) {
}

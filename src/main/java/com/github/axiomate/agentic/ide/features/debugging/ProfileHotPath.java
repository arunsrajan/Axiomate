package com.github.axiomate.agentic.ide.features.debugging;

/**
 * Model representing a detected performance bottleneck or hot execution path.
 */
public record ProfileHotPath(
        String className,
        String methodName,
        int lineNumber,
        double cpuTimePercentage, // e.g. 68.4%
        long invocationCount,
        String bottleneckType,    // e.g. "O(N^2) NESTED_LOOP", "SYNCHRONIZATION_LOCK_CONTENTION", "EXCESSIVE_ALLOCATION"
        String description
) {
}

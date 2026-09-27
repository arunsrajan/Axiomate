package com.github.axiomate.agentic.ide.features.execution;

/**
 * Benchmark metrics collected from a speculative implementation branch.
 */
public record BranchBenchmark(
        long executionTimeNanos,
        long memoryAllocatedBytes,
        int linesOfCode,
        int cyclomaticComplexity,
        double testPassRate, // 0.0 - 1.0 (e.g. 1.0 = 100%)
        double overallScore   // higher is better
) {
    public String getFormattedTime() {
        if (executionTimeNanos < 1_000_000) {
            return String.format("%.2f µs", executionTimeNanos / 1000.0);
        }
        return String.format("%.2f ms", executionTimeNanos / 1_000_000.0);
    }

    public String getFormattedMemory() {
        return String.format("%.1f KB", memoryAllocatedBytes / 1024.0);
    }
}

package com.github.axiomate.agentic.ide.features.execution;

/**
 * Represents an individual speculative implementation branch.
 */
public record SpeculativeBranch(
        String branchId,
        String approachName,
        String implementationCode,
        String rationale,
        BranchBenchmark benchmark
) {
}

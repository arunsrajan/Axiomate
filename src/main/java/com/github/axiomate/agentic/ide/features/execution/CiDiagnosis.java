package com.github.axiomate.agentic.ide.features.execution;

import java.util.List;

/**
 * Model representing the diagnosis and PR fix proposal for a failed CI pipeline.
 */
public record CiDiagnosis(
        String buildTool, // e.g. "Maven", "Gradle", "GitHub Actions", "JUnit"
        String failedStage,
        String rootCauseSummary,
        List<String> errorLines,
        String targetFile,
        int targetLineNumber,
        CiFixProposal proposedFix
) {
    public record CiFixProposal(
            String proposedDiff,
            String fixExplanation,
            String generatedPrTitle,
            String generatedPrBody,
            boolean automatedTestVerified
    ) {}
}

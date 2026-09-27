package com.github.axiomate.agentic.ide.features.planning;

import java.util.List;

/**
 * Result model for Blast Radius Preview analysis.
 */
public record BlastRadiusResult(
        String targetFile,
        List<String> directlyModifiedFiles,
        List<String> indirectlyAffectedFiles,
        List<String> affectedApis,
        List<String> affectedTests,
        List<String> downstreamServices,
        String riskLevel, // LOW, MEDIUM, HIGH, CRITICAL
        int impactScore,  // 0 - 100
        String rationale
) {
}

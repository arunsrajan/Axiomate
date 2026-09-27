package com.github.axiomate.agentic.ide.features.security;

import java.util.List;

/**
 * Result model for a vetted third-party software dependency.
 */
public record VettedDependency(
        String groupId,
        String artifactId,
        String version,
        String licenseType,        // e.g. "Apache-2.0", "MIT", "GPL-3.0"
        boolean licenseAllowed,    // true if permissive, false if viral/copyleft
        int maintenanceHealthScore,// 0 - 100
        int knownCveCount,
        List<String> cveIdentifiers,
        boolean approved,
        String vettingSummary
) {
}

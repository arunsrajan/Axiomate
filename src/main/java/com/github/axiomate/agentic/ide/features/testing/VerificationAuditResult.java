package com.github.axiomate.agentic.ide.features.testing;

import java.util.List;

/**
 * Result produced by the Independent Verifier Agent audit.
 */
public record VerificationAuditResult(
        boolean passed,
        int score, // 0 - 100
        String verdict, // "APPROVED", "CHANGES_REQUESTED", "REJECTED"
        List<String> verifiedRequirements,
        List<String> defectFindings,
        List<String> securityObservations,
        String auditSummary
) {
}

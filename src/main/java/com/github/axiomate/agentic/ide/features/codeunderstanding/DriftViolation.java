package com.github.axiomate.agentic.ide.features.codeunderstanding;

/**
 * An architectural drift violation flagged by the detector.
 */
public record DriftViolation(
        String ruleId,
        String sourceFile,
        int lineNumber,
        String forbiddenTarget,
        String violationMessage,
        String severity // WARNING, ERROR, BLOCKER
) {
}

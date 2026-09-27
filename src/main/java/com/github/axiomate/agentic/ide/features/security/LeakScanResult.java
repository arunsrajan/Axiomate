package com.github.axiomate.agentic.ide.features.security;

import java.util.List;

/**
 * Result model for credential leak detection.
 */
public record LeakScanResult(
        boolean leakDetected,
        int secretCount,
        List<DetectedSecret> detectedSecrets,
        String sanitizedText
) {
    public record DetectedSecret(
            String secretType, // e.g. "OPENAI_API_KEY", "AWS_ACCESS_KEY", "GITHUB_PAT", "PRIVATE_KEY"
            String maskedValue,
            int startIndex,
            int endIndex
    ) {}
}

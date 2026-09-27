package com.github.axiomate.agentic.ide.features.security;

import java.util.List;

/**
 * Result model for Prompt Injection Detection.
 */
public record InjectionScanResult(
        boolean injectionAttemptDetected,
        int threatScore, // 0 - 100
        List<String> detectedAttackVectors,
        String sanitizedDataContent,
        String shieldExplanation
) {
}

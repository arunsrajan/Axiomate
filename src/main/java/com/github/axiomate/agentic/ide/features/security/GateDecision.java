package com.github.axiomate.agentic.ide.features.security;

/**
 * Decision returned by the Irreversible Action Gate.
 */
public record GateDecision(
        boolean isDestructive,
        String operationType, // "FILE_DELETION", "DATABASE_DROP", "FORCE_PUSH", "HARD_RESET"
        String targetSubject,
        String warningRationale,
        boolean requiresHumanConfirmation
) {
    public static GateDecision safe(String operation, String subject) {
        return new GateDecision(false, operation, subject, "Operation is safe/reversible.", false);
    }

    public static GateDecision destructive(String operation, String subject, String rationale) {
        return new GateDecision(true, operation, subject, rationale, true);
    }
}

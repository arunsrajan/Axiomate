package com.github.axiomate.agentic.ide.features.security;

import java.time.Instant;

/**
 * Model representing a tamper-evident audit record of an agent action.
 */
public record AuditEntry(
        String auditId,
        Instant timestamp,
        String sessionId,
        String actionType,    // "USER_PROMPT", "TOOL_EXECUTION", "FILE_WRITE", "APPROVAL_GRANTED", "COMMAND_RUN"
        String actor,         // "USER", "AGENT", "SYSTEM"
        String targetResource,
        String detailsSummary,
        String sha256Signature,
        String previousHashChain
) {
}

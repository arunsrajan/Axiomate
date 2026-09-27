package com.github.axiomate.agentic.ide.features.devexperience;

import java.time.Instant;

/**
 * Model representing a batched notification or question queued by Focus Guardian.
 */
public record QueuedNotification(
        String id,
        String category, // "CLARIFICATION_QUESTION", "TOOL_STATUS", "APPROVAL_REQUEST", "MAINTENANCE_UPDATE"
        String title,
        String message,
        String priority, // "LOW", "NORMAL", "URGENT"
        Instant queuedAt
) {
}

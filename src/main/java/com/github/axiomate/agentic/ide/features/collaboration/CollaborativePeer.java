package com.github.axiomate.agentic.ide.features.collaboration;

import java.time.Instant;

/**
 * Model representing an active peer steering the agent collaboratively.
 */
public record CollaborativePeer(
        String peerId,
        String displayName,
        String email,
        String cursorPosition, // e.g. "Calculator.java:L42:C15"
        String activeIntent,   // e.g. "Steering agent to optimize memory allocation"
        Instant lastActiveAt,
        String colorHex
) {
}

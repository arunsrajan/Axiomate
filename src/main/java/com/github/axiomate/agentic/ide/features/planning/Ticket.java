package com.github.axiomate.agentic.ide.features.planning;

import java.util.List;

/**
 * Data model for an individual subtask/ticket decomposed from a specification.
 */
public record Ticket(
        String id,
        String title,
        String description,
        String tShirtSize, // S, M, L, XL
        int storyPoints,   // 1, 2, 3, 5, 8
        List<String> dependencyTicketIds,
        String targetAgentRole, // e.g. "Architect", "Backend Specialist", "Test Engineer", "Security Reviewer"
        List<String> acceptanceCriteria
) {
}

package com.github.axiomate.agentic.ide.features.codeunderstanding;

/**
 * Directed edge representing relationships in the Codebase Knowledge Graph.
 */
public record GraphEdge(
        String sourceId,
        String targetId,
        String relationType, // DEPENDS_ON, CALLS, OWNS, IMPLEMENTS, DATA_FLOW
        String description
) {
}

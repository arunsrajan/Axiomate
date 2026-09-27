package com.github.axiomate.agentic.ide.features.codeunderstanding;

import java.util.Map;

/**
 * Node in the Codebase Knowledge Graph.
 */
public record GraphNode(
        String id,
        String name,
        String type, // MODULE, PACKAGE, CLASS, METHOD, SERVICE
        String filePath,
        String owner,
        Map<String, String> metadata
) {
}

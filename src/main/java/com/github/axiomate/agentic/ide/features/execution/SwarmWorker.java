package com.github.axiomate.agentic.ide.features.execution;

import java.util.List;
import java.util.Map;

/**
 * Worker model representing an individual agent worker inside a swarm.
 */
public record SwarmWorker(
        String workerId,
        String workerRole,
        String assignedSubtask,
        String isolatedWorktreePath,
        Map<String, String> fileModifications, // relativeFilePath -> modifiedContent
        boolean completed
) {
}

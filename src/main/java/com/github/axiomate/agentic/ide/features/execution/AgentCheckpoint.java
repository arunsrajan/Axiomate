package com.github.axiomate.agentic.ide.features.execution;

import com.github.axiomate.agentic.ide.agent.AgentMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of agent state at a specific step in time.
 */
public record AgentCheckpoint(
        String checkpointId,
        int stepNumber,
        String stepDescription,
        Instant timestamp,
        String sessionId,
        List<AgentMessage> conversationHistory,
        Map<String, String> fileSnapshots, // filePath -> fileContent
        String activeFilePath,
        String parentCheckpointId
) {
}

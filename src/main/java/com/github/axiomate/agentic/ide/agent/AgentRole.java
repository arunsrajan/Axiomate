package com.github.axiomate.agentic.ide.agent;

public enum AgentRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
    TOOL_CALL,
    THINKING,
    /** The files a run changed, with lines added and removed (see FileChangeTracker#describe). */
    FILE_CHANGES
}


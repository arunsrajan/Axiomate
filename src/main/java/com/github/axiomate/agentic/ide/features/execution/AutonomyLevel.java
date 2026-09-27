package com.github.axiomate.agentic.ide.features.execution;

/**
 * Autonomy levels for Agentic AI execution.
 */
public enum AutonomyLevel {
    SUGGEST_ONLY("Suggest Only", "Read-only suggestions. Never edits files or executes terminal commands."),
    EDIT_WITH_APPROVAL("Edit with Approval", "Inspects and crafts changes, but prompts for user confirmation before writing or running."),
    FULLY_AUTONOMOUS("Fully Autonomous", "Executes file modifications, tools, and tests autonomously without blocking.");

    private final String displayName;
    private final String description;

    AutonomyLevel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}

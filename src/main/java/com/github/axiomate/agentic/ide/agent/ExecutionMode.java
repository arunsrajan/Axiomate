package com.github.axiomate.agentic.ide.agent;

/**
 * How freely the agent may use its tools, chosen per session.
 */
public enum ExecutionMode {

    /** Investigate with read-only tools and finish with an implementation plan; nothing is changed. */
    PLAN("Plan", "Read and search only, then propose an implementation plan. Edits and commands are refused."),
    /** Ask before every tool call that changes files or runs commands. */
    MANUAL("Manual", "Ask before each tool call that changes files or runs commands."),
    /** Run tools without asking (destructive commands still ask). */
    AUTO("Auto", "Run tools without asking. Destructive commands still ask for confirmation.");

    private final String label;
    private final String description;

    ExecutionMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** The next mode in Plan → Manual → Auto → Plan order (Shift+Tab in the prompt box). */
    public ExecutionMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** The mode named {@code text} ("plan", "Manual", "AUTO"), or null. */
    public static ExecutionMode parse(String text) {
        if (text == null) return null;
        for (ExecutionMode m : values()) {
            if (m.name().equalsIgnoreCase(text.trim()) || m.label.equalsIgnoreCase(text.trim())) return m;
        }
        return null;
    }

    @Override
    public String toString() {
        return label;
    }
}

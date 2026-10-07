package com.github.axiomate.agentic.ide.interop;

/**
 * Where an agent memory file lives: inside the project, or in the user's home directory.
 */
public enum MemoryScope {
    /** Relative to the project root, shared with the team through version control. */
    PROJECT("Project"),
    /** Relative to the user's home directory, applies to every project. */
    USER("User (global)");

    private final String label;

    MemoryScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}

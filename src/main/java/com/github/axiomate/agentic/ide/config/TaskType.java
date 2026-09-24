package com.github.axiomate.agentic.ide.config;

/**
 * Task categories used for intelligent multi-provider model routing.
 */
public enum TaskType {
    GENERAL("General Coding & Conversation"),
    EXPLAIN("Code Explanation & Architecture Walkthrough"),
    REFACTOR("Complex Refactoring & Modernization"),
    GENERATE_TESTS("Automated Unit Test Generation"),
    DEBUG_FIX("Bug Diagnosis & Deep Analytical Fixes"),
    TERMINAL_TOOL("Terminal & MCP Tool Orchestration");

    private final String description;

    TaskType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}


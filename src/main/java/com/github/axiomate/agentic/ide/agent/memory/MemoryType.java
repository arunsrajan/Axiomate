package com.github.axiomate.agentic.ide.agent.memory;

/**
 * Categorization for Agentic AI Memory.
 */
public enum MemoryType {
    /**
     * Working memory: active execution context, immediate goal, scratchpad.
     */
    WORKING,

    /**
     * Long-term memory: persistent knowledge, architectural rules, domain facts.
     */
    LONG_TERM,

    /**
     * Episodic memory: past interactions, tasks executed, successes/failures.
     */
    EPISODIC,

    /**
     * Project rules: repository-specific guidelines, coding standards, instructions.
     */
    PROJECT_RULE
}


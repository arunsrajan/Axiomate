package com.github.axiomate.agentic.ide.features.debugging;

import java.util.Map;

/**
 * Model representing a symbolic breakpoint set by the Live Debugger Agent.
 */
public record DebugBreakpoint(
        String id,
        String sourceFile,
        int lineNumber,
        String condition, // e.g. "count > 100", "user == null"
        boolean hit,
        Map<String, String> capturedVariables
) {
}

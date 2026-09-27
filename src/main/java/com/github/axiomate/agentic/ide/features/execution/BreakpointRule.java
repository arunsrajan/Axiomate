package com.github.axiomate.agentic.ide.features.execution;

/**
 * Rule definition for Human-in-the-loop Breakpoints.
 */
public record BreakpointRule(
        String id,
        String name,
        String pathPattern, // e.g. "*auth*", "*migration*", "src/security/**"
        String actionType,  // "EDIT", "COMMAND", "DELETE", "ALL"
        String reason
) {
    public boolean matches(String filePath, String action) {
        if (actionType != null && !actionType.equalsIgnoreCase("ALL") && !actionType.equalsIgnoreCase(action)) {
            return false;
        }
        if (pathPattern == null || pathPattern.isBlank() || pathPattern.equals("*")) {
            return true;
        }

        String normPath = filePath != null ? filePath.replace('\\', '/').toLowerCase() : "";
        String normPattern = pathPattern.replace('\\', '/').toLowerCase();

        if (normPattern.startsWith("*") && normPattern.endsWith("*")) {
            String mid = normPattern.substring(1, normPattern.length() - 1);
            return normPath.contains(mid);
        } else if (normPattern.contains("**")) {
            String prefix = normPattern.replace("/**", "").replace("**", "");
            return normPath.contains(prefix);
        }
        return normPath.contains(normPattern);
    }
}

package com.github.axiomate.agentic.ide.features.extensibility;

/**
 * Model representing an enterprise Policy-as-Code rule for autonomous agents.
 */
public record AgentPolicyRule(
        String ruleId,
        String name,
        String pathGlob,       // e.g. "**/billing/**", "**/auth/**", "src/main/resources/application.properties"
        String forbiddenAction,// "WRITE", "DELETE", "EXECUTE", "ANY"
        String enforcementLevel,// "DENY", "REQUIRE_APPROVAL", "AUDIT_ONLY"
        String rationale
) {
    public boolean matches(String filePath, String action) {
        if (forbiddenAction != null && !forbiddenAction.equalsIgnoreCase("ANY") && !forbiddenAction.equalsIgnoreCase(action)) {
            return false;
        }
        if (pathGlob == null || pathGlob.isBlank() || pathGlob.equals("*")) return true;

        String normPath = filePath != null ? filePath.replace('\\', '/').toLowerCase() : "";
        String normGlob = pathGlob.replace('\\', '/').toLowerCase();

        if (normGlob.startsWith("**/") && normGlob.endsWith("/**")) {
            String mid = normGlob.substring(3, normGlob.length() - 3);
            return normPath.contains(mid);
        } else if (normGlob.startsWith("**/")) {
            String suffix = normGlob.substring(3);
            return normPath.endsWith(suffix) || normPath.contains(suffix);
        } else if (normGlob.endsWith("/**")) {
            String prefix = normGlob.substring(0, normGlob.length() - 3);
            return normPath.startsWith(prefix) || normPath.contains(prefix);
        }
        return normPath.contains(normGlob);
    }
}

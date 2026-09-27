package com.github.axiomate.agentic.ide.features.extensibility;

import java.util.List;

/**
 * Model defining a specialized autonomous agent role with its own tools, prompt, and rules.
 */
public record AgentRoleDefinition(
        String roleId,
        String roleName,
        String description,
        String specializedSystemPrompt,
        List<String> allowedTools,
        String riskTolerance, // CONSERVATIVE, BALANCED, AGGRESSIVE
        List<String> specializations
) {
}

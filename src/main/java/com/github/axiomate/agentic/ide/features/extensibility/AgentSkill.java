package com.github.axiomate.agentic.ide.features.extensibility;

import java.util.List;

/**
 * A synthesized, reusable agent skill generated from recorded developer workflows.
 */
public record AgentSkill(
        String skillId,
        String skillName,
        String description,
        List<String> requiredInputs,
        List<SkillStep> steps,
        String skillMarkdownDoc
) {
    public record SkillStep(
            int stepNumber,
            String commandOrTool,
            String description,
            String payloadTemplate
    ) {}
}

package com.github.agentforge.agentic.ide.agent.router;

import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.config.ProviderConfig;
import com.github.agentforge.agentic.ide.config.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intelligent task classifier and multi-provider model router.
 * Automatically chooses the optimal model and provider based on task complexity and user configuration.
 */
public class AutonomousTaskRouter {

    private static final Logger log = LoggerFactory.getLogger(AutonomousTaskRouter.class);

    public record RoutedModel(TaskType taskType, String providerId, String modelId, String rationale) {}

    public static TaskType classifyTask(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return TaskType.GENERAL;
        }

        String lower = prompt.toLowerCase();
        if (lower.contains("test") || lower.contains("junit") || lower.contains("assert") || lower.contains("mock")) {
            return TaskType.GENERATE_TESTS;
        }
        if (lower.contains("refactor") || lower.contains("modernize") || lower.contains("clean code") || lower.contains("optimize") || lower.contains("architect")) {
            return TaskType.REFACTOR;
        }
        if (lower.contains("explain") || lower.contains("what does") || lower.contains("walkthrough") || lower.contains("how does") || lower.contains("overview")) {
            return TaskType.EXPLAIN;
        }
        if (lower.contains("bug") || lower.contains("fix") || lower.contains("error") || lower.contains("exception") || lower.contains("diagnose")) {
            return TaskType.DEBUG_FIX;
        }
        if (lower.contains("run") || lower.contains("terminal") || lower.contains("command") || lower.contains("mcp") || lower.contains("mvn")) {
            return TaskType.TERMINAL_TOOL;
        }
        return TaskType.GENERAL;
    }

    public static RoutedModel route(String prompt, String defaultProviderId, String defaultModelId) {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        TaskType taskType = classifyTask(prompt);

        if (!config.isAutoRoutingEnabled()) {
            return new RoutedModel(taskType, defaultProviderId, defaultModelId, "Manual / Session Model Selected");
        }
        String targetRoute = config.getTaskRouting().get(taskType);

        if (targetRoute != null && targetRoute.contains(":")) {
            String[] parts = targetRoute.split(":", 2);
            String providerId = parts[0];
            String modelId = parts[1];

            ProviderConfig prov = config.getProvider(providerId);
            if (prov != null && prov.isEnabled()) {
                String rationale = String.format("Auto-routed task [%s] to %s (%s)",
                        taskType.name(), prov.getName(), modelId);
                log.info(rationale);
                return new RoutedModel(taskType, providerId, modelId, rationale);
            }
        }

        // Fallback to active selection
        return new RoutedModel(taskType, defaultProviderId, defaultModelId, "Default Active Provider/Model");
    }
}

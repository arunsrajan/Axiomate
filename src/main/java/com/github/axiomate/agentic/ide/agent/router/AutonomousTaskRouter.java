package com.github.axiomate.agentic.ide.agent.router;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.config.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intelligent task classifier and multi-provider model router.
 * Automatically chooses the optimal model and provider based on task complexity and user configuration.
 */
public class AutonomousTaskRouter {

    private static final Logger log = LoggerFactory.getLogger(AutonomousTaskRouter.class);

    public record RoutedModel(TaskType taskType, String providerId, String modelId, String rationale) {}

    // Whole words only: substrings misfire ("latest" is not a test task, "prefix" not a bug fix, "trunk" not a command)
    private static final java.util.regex.Pattern TESTS = java.util.regex.Pattern.compile(
            "\\b(tests?|testing|testcases?|junit\\d*|asserts?|assertions?|mocks?|mocking|mockito)\\b");
    private static final java.util.regex.Pattern REFACTOR = java.util.regex.Pattern.compile(
            "\\b(refactor\\w*|moderni[sz]\\w*|clean[- ]?code|optimi[sz]\\w*|architect\\w*)\\b");
    private static final java.util.regex.Pattern EXPLAIN = java.util.regex.Pattern.compile(
            "\\b(explain\\w*|what does|walk ?through|how does|overview)\\b");
    private static final java.util.regex.Pattern DEBUG = java.util.regex.Pattern.compile(
            "\\b(bugs?|buggy|fix|fixes|fixing|errors?|\\w*exceptions?|diagnos\\w*|crash\\w*|stack ?trace)\\b");
    private static final java.util.regex.Pattern TERMINAL = java.util.regex.Pattern.compile(
            "\\b(run|runs|running|terminal|commands?|mcp|mvn|gradle|npm|shell|bash)\\b");

    public static TaskType classifyTask(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return TaskType.GENERAL;
        }

        String lower = prompt.toLowerCase(java.util.Locale.ROOT);
        if (TESTS.matcher(lower).find()) return TaskType.GENERATE_TESTS;
        if (REFACTOR.matcher(lower).find()) return TaskType.REFACTOR;
        if (EXPLAIN.matcher(lower).find()) return TaskType.EXPLAIN;
        if (DEBUG.matcher(lower).find()) return TaskType.DEBUG_FIX;
        if (TERMINAL.matcher(lower).find()) return TaskType.TERMINAL_TOOL;
        return TaskType.GENERAL;
    }

    public static RoutedModel route(String prompt, String defaultProviderId, String defaultModelId) {
        return route(prompt, defaultProviderId, defaultModelId, ConfigManager.getInstance().getConfig().isAutoRoutingEnabled());
    }

    public static RoutedModel route(String prompt, String defaultProviderId, String defaultModelId, boolean autoRoutingEnabled) {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        TaskType taskType = classifyTask(prompt);

        if (!autoRoutingEnabled) {
            return new RoutedModel(taskType, defaultProviderId, defaultModelId, "Manual / Session Model Selected");
        }

        // If the active session is explicitly configured with a custom provider (e.g. CUSTOM_ANTHROPIC),
        // honor that custom provider instead of overriding it with external default routes
        if (defaultProviderId != null && !defaultProviderId.isBlank()) {
            ProviderConfig currentProv = config.getProvider(defaultProviderId);
            if (currentProv != null && currentProv.isEnabled()) {
                if (defaultProviderId.startsWith("CUSTOM_") || defaultProviderId.contains("CUSTOM")) {
                    return new RoutedModel(taskType, defaultProviderId, defaultModelId,
                            String.format("Using active custom provider %s [%s] for %s",
                                    currentProv.getName(), defaultModelId, taskType.name()));
                }
            }
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


package com.github.axiomate.agentic.ide.features.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 7: Autonomy dial per task.
 * Choose between suggest-only, edit-with-approval, and fully autonomous,
 * set by directory or risk level.
 */
public class AutonomyDial {

    private static final Logger log = LoggerFactory.getLogger(AutonomyDial.class);
    private static AutonomyDial instance;

    private AutonomyLevel globalLevel = AutonomyLevel.EDIT_WITH_APPROVAL;
    private final Map<String, AutonomyLevel> pathOverrides = new ConcurrentHashMap<>();
    private final Map<String, AutonomyLevel> riskOverrides = new ConcurrentHashMap<>();
    private final Map<String, AutonomyLevel> taskOverrides = new ConcurrentHashMap<>();

    private AutonomyDial() {
        // High risk / critical folders default to restricted autonomy
        pathOverrides.put("src/main/resources/security", AutonomyLevel.SUGGEST_ONLY);
        pathOverrides.put(".git", AutonomyLevel.SUGGEST_ONLY);
        pathOverrides.put("src/auth", AutonomyLevel.EDIT_WITH_APPROVAL);
        pathOverrides.put("db/migration", AutonomyLevel.EDIT_WITH_APPROVAL);

        // Risk level defaults
        riskOverrides.put("CRITICAL", AutonomyLevel.SUGGEST_ONLY);
        riskOverrides.put("HIGH", AutonomyLevel.EDIT_WITH_APPROVAL);
        riskOverrides.put("MEDIUM", AutonomyLevel.EDIT_WITH_APPROVAL);
        riskOverrides.put("LOW", AutonomyLevel.FULLY_AUTONOMOUS);
    }

    public static synchronized AutonomyDial getInstance() {
        if (instance == null) {
            instance = new AutonomyDial();
        }
        return instance;
    }

    public AutonomyLevel getGlobalLevel() {
        return globalLevel;
    }

    public void setGlobalLevel(AutonomyLevel globalLevel) {
        if (globalLevel != null) {
            this.globalLevel = globalLevel;
            log.info("Set global autonomy level to: {}", globalLevel);
        }
    }

    public void setPathOverride(String pathPrefix, AutonomyLevel level) {
        if (pathPrefix != null && level != null) {
            pathOverrides.put(pathPrefix.replace('\\', '/'), level);
        }
    }

    public void removePathOverride(String pathPrefix) {
        if (pathPrefix != null) {
            pathOverrides.remove(pathPrefix.replace('\\', '/'));
        }
    }

    public void setRiskOverride(String riskLevel, AutonomyLevel level) {
        if (riskLevel != null && level != null) {
            riskOverrides.put(riskLevel.toUpperCase(), level);
        }
    }

    public void setTaskOverride(String taskId, AutonomyLevel level) {
        if (taskId != null && level != null) {
            taskOverrides.put(taskId, level);
        }
    }

    /**
     * Resolves effective autonomy level for a given task, file path, and risk level.
     */
    public AutonomyLevel resolveLevel(String taskId, String filePath, String riskLevel) {
        if (taskId != null && taskOverrides.containsKey(taskId)) {
            return taskOverrides.get(taskId);
        }

        if (filePath != null) {
            String norm = filePath.replace('\\', '/');
            for (Map.Entry<String, AutonomyLevel> entry : pathOverrides.entrySet()) {
                if (norm.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        if (riskLevel != null && riskOverrides.containsKey(riskLevel.toUpperCase())) {
            return riskOverrides.get(riskLevel.toUpperCase());
        }

        return globalLevel;
    }

    public boolean canExecuteWithoutApproval(String taskId, String filePath, String riskLevel) {
        return resolveLevel(taskId, filePath, riskLevel) == AutonomyLevel.FULLY_AUTONOMOUS;
    }

    public boolean isSuggestOnly(String taskId, String filePath, String riskLevel) {
        return resolveLevel(taskId, filePath, riskLevel) == AutonomyLevel.SUGGEST_ONLY;
    }
}

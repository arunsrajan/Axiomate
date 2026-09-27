package com.github.axiomate.agentic.ide.features.codeunderstanding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 16: Architecture drift detector.
 * It flags changes that violate documented layering or design rules.
 */
public class ArchitectureDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureDriftDetector.class);
    private static ArchitectureDriftDetector instance;

    private final List<LayerRule> rules = new CopyOnWriteArrayList<>();

    private ArchitectureDriftDetector() {
        initDefaultRules();
    }

    public static synchronized ArchitectureDriftDetector getInstance() {
        if (instance == null) {
            instance = new ArchitectureDriftDetector();
        }
        return instance;
    }

    private void initDefaultRules() {
        // UI layer should not bypass AgentManager / AIAgentService to manipulate memory stores directly
        rules.add(new LayerRule(
                "rule-no-ui-direct-memory-store",
                "UI cannot directly access low-level JsonAgentMemoryStore",
                "ui",
                "JsonAgentMemoryStore",
                "UI components must use MemoryManager or AIAgentService, never instantiate internal JsonAgentMemoryStore directly."
        ));

        // Domain models should not depend on UI classes
        rules.add(new LayerRule(
                "rule-domain-no-ui",
                "Core Domain/Config cannot depend on UI Swing components",
                "config",
                "javax.swing",
                "Headless core configuration classes must never import Swing/AWT UI widgets."
        ));

        // Tools should not import Swing UI classes directly
        rules.add(new LayerRule(
                "rule-tools-no-ui",
                "Autonomous Agent Tools must remain headless",
                "agent.tools",
                "javax.swing.JFrame",
                "Autonomous tools run on background executor threads and must not invoke modal Swing dialogs directly."
        ));
    }

    public List<LayerRule> getRules() {
        return new ArrayList<>(rules);
    }

    public void addRule(LayerRule rule) {
        if (rule != null) {
            rules.add(rule);
        }
    }

    /**
     * Inspects a file's content for architecture layering drift violations.
     */
    public List<DriftViolation> detectDrift(String sourceFilePath, String fileContent) {
        List<DriftViolation> violations = new ArrayList<>();
        if (sourceFilePath == null || fileContent == null) return violations;

        String normPath = sourceFilePath.replace('\\', '/');
        String[] lines = fileContent.split("\n");

        for (LayerRule rule : rules) {
            if (normPath.contains(rule.fromLayerPackage())) {
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if ((line.startsWith("import ") || line.contains("new ")) && line.contains(rule.forbiddenToLayerPackage())) {
                        violations.add(new DriftViolation(
                                rule.id(),
                                sourceFilePath,
                                i + 1,
                                rule.forbiddenToLayerPackage(),
                                "Architectural drift: " + rule.rationale(),
                                "ERROR"
                        ));
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            log.warn("Architecture drift detector found {} violation(s) in '{}'", violations.size(), sourceFilePath);
        }
        return violations;
    }
}

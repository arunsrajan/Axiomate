package com.github.axiomate.agentic.ide.features.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 14: Human-in-the-loop breakpoints.
 * You set "pause here" conditions, for example before touching auth code or running a migration.
 */
public class HumanInTheLoopGate {

    private static final Logger log = LoggerFactory.getLogger(HumanInTheLoopGate.class);
    private static HumanInTheLoopGate instance;

    public interface ApprovalCallback {
        boolean requestApproval(String ruleName, String filePath, String action, String reason);
    }

    private final List<BreakpointRule> rules = new CopyOnWriteArrayList<>();
    private ApprovalCallback approvalCallback;

    private HumanInTheLoopGate() {
        initDefaultRules();
    }

    public static synchronized HumanInTheLoopGate getInstance() {
        if (instance == null) {
            instance = new HumanInTheLoopGate();
        }
        return instance;
    }

    private void initDefaultRules() {
        rules.add(new BreakpointRule(
                "bp-auth",
                "Authentication & Credentials Gate",
                "*auth*",
                "ALL",
                "Modification touches security or authentication subsystem. Mandatory developer sign-off required."
        ));

        rules.add(new BreakpointRule(
                "bp-migration",
                "Database Migration Gate",
                "*migration*",
                "ALL",
                "Execution touches database schema or migration scripts. Data loss prevention guard."
        ));

        rules.add(new BreakpointRule(
                "bp-security",
                "Security & Cryptography Gate",
                "*security*",
                "ALL",
                "Sensitive security configuration or crypto keys detected."
        ));
    }

    public List<BreakpointRule> getRules() {
        return new ArrayList<>(rules);
    }

    public void addRule(BreakpointRule rule) {
        if (rule != null) {
            rules.add(rule);
            log.info("Added Human-in-the-loop breakpoint rule: {}", rule.name());
        }
    }

    public void removeRule(String ruleId) {
        rules.removeIf(r -> r.id().equalsIgnoreCase(ruleId));
    }

    public void setApprovalCallback(ApprovalCallback callback) {
        this.approvalCallback = callback;
    }

    /**
     * Checks if the proposed operation triggers any breakpoint.
     * If a breakpoint is hit, prompts the user callback or returns requirement for approval.
     */
    public Optional<BreakpointRule> checkBreakpoint(String filePath, String action) {
        for (BreakpointRule rule : rules) {
            if (rule.matches(filePath, action)) {
                log.info("Operation on '{}' triggered breakpoint rule: {}", filePath, rule.name());
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public boolean shouldPauseForApproval(String filePath, String action) {
        Optional<BreakpointRule> hit = checkBreakpoint(filePath, action);
        if (hit.isEmpty()) {
            return false;
        }

        BreakpointRule rule = hit.get();
        if (approvalCallback != null) {
            return !approvalCallback.requestApproval(rule.name(), filePath, action, rule.reason());
        }
        // If no interactive callback is registered, default to pausing/blocking
        return true;
    }
}

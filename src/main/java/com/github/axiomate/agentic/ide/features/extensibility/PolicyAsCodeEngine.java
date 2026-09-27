package com.github.axiomate.agentic.ide.features.extensibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 45: Policy-as-code for agents.
 * Org-wide rules such as "never touch /billing without approval" are enforced by the IDE.
 */
public class PolicyAsCodeEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyAsCodeEngine.class);
    private static PolicyAsCodeEngine instance;

    private final List<AgentPolicyRule> policies = new CopyOnWriteArrayList<>();

    private PolicyAsCodeEngine() {
        initDefaultPolicies();
    }

    public static synchronized PolicyAsCodeEngine getInstance() {
        if (instance == null) {
            instance = new PolicyAsCodeEngine();
        }
        return instance;
    }

    private void initDefaultPolicies() {
        policies.add(new AgentPolicyRule(
                "pol-billing",
                "Strict Billing & Financial Protection",
                "**/billing/**",
                "ANY",
                "REQUIRE_APPROVAL",
                "Org policy: Modifications to payment gateways, invoicing, or /billing directories require mandatory human lead approval."
        ));

        policies.add(new AgentPolicyRule(
                "pol-prod-config",
                "Production Configuration Write Deny",
                "**/application-prod.properties",
                "WRITE",
                "DENY",
                "Org policy: Direct autonomous editing of production property configs is strictly forbidden."
        ));

        policies.add(new AgentPolicyRule(
                "pol-crypto-keys",
                "Private Keystores Protection",
                "**/*.keystore",
                "ANY",
                "DENY",
                "Org policy: Agents are prohibited from reading or altering cryptographic keystores."
        ));
    }

    public List<AgentPolicyRule> getPolicies() {
        return new ArrayList<>(policies);
    }

    public void addPolicy(AgentPolicyRule rule) {
        if (rule != null) {
            policies.add(rule);
            log.info("Registered Policy-as-Code rule: {}", rule.name());
        }
    }

    public void removePolicy(String ruleId) {
        policies.removeIf(p -> p.ruleId().equalsIgnoreCase(ruleId));
    }

    /**
     * Evaluates proposed agent action against all registered organizational policies.
     */
    public PolicyValidationResult evaluate(String filePath, String action) {
        for (AgentPolicyRule rule : policies) {
            if (rule.matches(filePath, action)) {
                log.warn("Policy-as-code rule triggered: {} on {}:{}", rule.name(), filePath, action);
                if ("DENY".equalsIgnoreCase(rule.enforcementLevel())) {
                    return PolicyValidationResult.deny(rule);
                } else if ("REQUIRE_APPROVAL".equalsIgnoreCase(rule.enforcementLevel())) {
                    return PolicyValidationResult.requireApproval(rule);
                }
            }
        }
        return PolicyValidationResult.allow();
    }
}

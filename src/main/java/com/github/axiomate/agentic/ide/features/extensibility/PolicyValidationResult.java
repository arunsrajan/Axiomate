package com.github.axiomate.agentic.ide.features.extensibility;

/**
 * Result returned by the Policy-as-Code Engine.
 */
public record PolicyValidationResult(
        boolean allowed,
        String enforcementLevel, // "ALLOW", "DENY", "REQUIRE_APPROVAL"
        AgentPolicyRule triggeringRule,
        String message
) {
    public static PolicyValidationResult allow() {
        return new PolicyValidationResult(true, "ALLOW", null, "Complies with all enterprise agent policies.");
    }

    public static PolicyValidationResult deny(AgentPolicyRule rule) {
        return new PolicyValidationResult(false, "DENY", rule, "Blocked by Policy-as-Code: " + rule.rationale());
    }

    public static PolicyValidationResult requireApproval(AgentPolicyRule rule) {
        return new PolicyValidationResult(false, "REQUIRE_APPROVAL", rule, "Policy requires explicit developer approval: " + rule.rationale());
    }
}

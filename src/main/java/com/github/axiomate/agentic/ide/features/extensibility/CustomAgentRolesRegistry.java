package com.github.axiomate.agentic.ide.features.extensibility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 42: Custom agent roles.
 * Define specialists such as a "security reviewer," "DB migration expert," or "accessibility auditor"
 * with their own tools and rules.
 */
public class CustomAgentRolesRegistry {

    private static final Logger log = LoggerFactory.getLogger(CustomAgentRolesRegistry.class);
    private static CustomAgentRolesRegistry instance;

    private final Map<String, AgentRoleDefinition> roles = new ConcurrentHashMap<>();

    private CustomAgentRolesRegistry() {
        initDefaultRoles();
    }

    public static synchronized CustomAgentRolesRegistry getInstance() {
        if (instance == null) {
            instance = new CustomAgentRolesRegistry();
        }
        return instance;
    }

    private void initDefaultRoles() {
        registerRole(new AgentRoleDefinition(
                "role-security",
                "Security Reviewer",
                "Specialist in credential detection, prompt-injection defense, CVE supply-chain auditing, and crypto safety.",
                "You are an adversarial Security Reviewer. Prioritize identifying vulnerabilities, secret leakage, and injection risks.",
                List.of("code_editor", "secret_leak_guard", "prompt_shield", "supply_chain_vetter"),
                "CONSERVATIVE",
                List.of("OWASP Top 10", "CVE Audits", "Secret Detection", "Safe Cryptography")
        ));

        registerRole(new AgentRoleDefinition(
                "role-db-migration",
                "DB Migration Expert",
                "Specialist in zero-downtime database schema migrations, index optimization, and SQL rollback scripts.",
                "You are a Database Migration Expert. Ensure all schema transformations are backwards-compatible and include down-migrations.",
                List.of("code_editor", "terminal", "blast_radius"),
                "CONSERVATIVE",
                List.of("Flyway / Liquibase", "PostgreSQL / MySQL", "Zero-Downtime DDL", "Query Plan Optimization")
        ));

        registerRole(new AgentRoleDefinition(
                "role-a11y",
                "Accessibility Auditor",
                "Specialist in WCAG 2.1 AA compliance, screen reader support, ARIA semantics, and keyboard navigation.",
                "You are an Accessibility Auditor. Scrutinize UI layouts for high-contrast palettes, keyboard focus traps, and screen-reader accessibility.",
                List.of("code_editor", "visual_regression"),
                "BALANCED",
                List.of("WCAG 2.1 AA", "Keyboard Traps", "Screen Readers", "Color Contrast")
        ));

        registerRole(new AgentRoleDefinition(
                "role-perf",
                "Performance Architect",
                "Specialist in hot path profiling, algorithmic complexity reduction, memory leak prevention, and cache design.",
                "You are a Performance Architect. Identify algorithmic bottlenecks and prove improvements via microbenchmarks.",
                List.of("code_editor", "performance_profiler", "terminal"),
                "AGGRESSIVE",
                List.of("JMH Benchmarks", "Low-Latency Collections", "Lock-Free Concurrency", "Zero-Allocation")
        ));
    }

    public List<AgentRoleDefinition> getAllRoles() {
        return new ArrayList<>(roles.values());
    }

    public Optional<AgentRoleDefinition> getRole(String roleId) {
        return Optional.ofNullable(roles.get(roleId));
    }

    public void registerRole(AgentRoleDefinition role) {
        if (role != null) {
            roles.put(role.roleId(), role);
            log.info("Registered custom agent role: {}", role.roleName());
        }
    }

    public void unregisterRole(String roleId) {
        if (roleId != null) {
            roles.remove(roleId);
        }
    }
}

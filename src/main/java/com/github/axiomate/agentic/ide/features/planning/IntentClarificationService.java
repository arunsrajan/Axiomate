package com.github.axiomate.agentic.ide.features.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Feature 1: Intent clarification loop.
 * Before a large change, the agent lists its assumptions and ambiguities so the developer
 * can resolve them in one pass.
 */
public class IntentClarificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentClarificationService.class);
    private static IntentClarificationService instance;

    private IntentClarificationService() {}

    public static synchronized IntentClarificationService getInstance() {
        if (instance == null) {
            instance = new IntentClarificationService();
        }
        return instance;
    }

    /**
     * Analyzes prompt and active context to identify ambiguities and unstated assumptions.
     */
    public List<ClarificationQuestion> analyzeIntent(String prompt, String activeFilePath) {
        List<ClarificationQuestion> questions = new ArrayList<>();
        String lower = prompt != null ? prompt.toLowerCase() : "";

        // 1. Scope / Target boundaries
        if (lower.contains("refactor") || lower.contains("rewrite") || lower.contains("redesign")) {
            questions.add(new ClarificationQuestion(
                    "scope_boundary",
                    "Scope & Blast Radius",
                    "Should the refactoring preserve strict binary/API backwards compatibility?",
                    "Yes, preserve existing public method signatures and deprecate old APIs",
                    List.of("Yes, strict compatibility", "No, breaking changes allowed", "Partial, preserve core API only"),
                    null
            ));
        }

        // 2. Database / Migration assumptions
        if (lower.contains("database") || lower.contains("db") || lower.contains("table") || lower.contains("migration") || lower.contains("sql")) {
            questions.add(new ClarificationQuestion(
                    "data_migration",
                    "Data Integrity",
                    "Should schema migration scripts include an automated rollback (down migration)?",
                    "Yes, generate both up and down migrations with zero-downtime column additions",
                    List.of("Yes, generate up and down", "Only up migration needed", "Manual migration"),
                    null
            ));
        }

        // 3. Testing strategy
        if (lower.contains("feature") || lower.contains("implement") || lower.contains("add") || lower.contains("create")) {
            questions.add(new ClarificationQuestion(
                    "test_strategy",
                    "Testing Strategy",
                    "Which level of automated tests should accompany this change?",
                    "Generate Unit tests (JUnit 5) and integration mock tests",
                    List.of("Unit tests only", "Unit + Integration tests", "Property-based tests", "None for now"),
                    null
            ));
        }

        // 4. Concurrency & Thread safety
        if (lower.contains("cache") || lower.contains("async") || lower.contains("service") || lower.contains("manager")) {
            questions.add(new ClarificationQuestion(
                    "thread_safety",
                    "Concurrency",
                    "Is this component accessed concurrently across multiple worker threads?",
                    "Yes, design as thread-safe using ConcurrentHashMap or synchronized locks",
                    List.of("Yes, thread-safe required", "No, single-threaded execution only", "Stateless"),
                    null
            ));
        }

        // 5. Error handling policy
        if (lower.contains("api") || lower.contains("client") || lower.contains("http") || lower.contains("endpoint")) {
            questions.add(new ClarificationQuestion(
                    "error_handling",
                    "Error Handling",
                    "What error handling and retry policy should be implemented?",
                    "Exponential backoff with max 3 retries and custom domain exception translation",
                    List.of("Exponential backoff (3 retries)", "Fail-fast without retry", "Circuit breaker pattern"),
                    null
            ));
        }

        // If no domain-specific questions triggered, provide general architectural assumption
        if (questions.isEmpty()) {
            questions.add(new ClarificationQuestion(
                    "implementation_style",
                    "Code Style & Architecture",
                    "Should the implementation adhere to the established package conventions?",
                    "Follow existing project naming, immutability conventions, and SLF4J logging",
                    List.of("Follow existing project conventions", "Standard Java 21 idioms", "Minimal boilerplate"),
                    null
            ));
        }

        log.info("Generated {} intent clarification questions for prompt: '{}'", questions.size(), prompt);
        return questions;
    }

    /**
     * Resolves questions in one pass and synthesizes an unambiguous instruction prompt.
     */
    public String synthesizeClarifiedPrompt(String originalPrompt, List<ClarificationQuestion> resolvedQuestions) {
        StringBuilder sb = new StringBuilder();
        sb.append(originalPrompt).append("\n\n");
        sb.append("### Clarified Intent & Resolved Assumptions (One-Pass Verification):\n");
        for (ClarificationQuestion q : resolvedQuestions) {
            String answer = q.resolvedAnswer() != null && !q.resolvedAnswer().isBlank()
                    ? q.resolvedAnswer()
                    : q.assumedDefault();
            sb.append("- **").append(q.question()).append("** -> *").append(answer).append("*\n");
        }
        return sb.toString();
    }
}

package com.github.axiomate.agentic.ide.features.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Feature 5: Spec-to-ticket decomposition.
 * A PRD or issue is broken into sized, dependency-ordered subtasks that can go to separate agents.
 */
public class SpecToTicketDecomposer {

    private static final Logger log = LoggerFactory.getLogger(SpecToTicketDecomposer.class);
    private static SpecToTicketDecomposer instance;

    private SpecToTicketDecomposer() {}

    public static synchronized SpecToTicketDecomposer getInstance() {
        if (instance == null) {
            instance = new SpecToTicketDecomposer();
        }
        return instance;
    }

    public List<Ticket> decompose(String specTitle, String specDescription) {
        List<Ticket> tickets = new ArrayList<>();
        String combined = (specTitle + " " + specDescription).toLowerCase();

        // 1. Domain Modeling & API Contracts
        tickets.add(new Ticket(
                "TICKET-1",
                "Define Core Domain Models and Contract Interfaces",
                "Synthesize required value objects, records, and service interface definitions for " + specTitle,
                "S",
                2,
                List.of(),
                "Code Architect",
                List.of("Interfaces defined with clean docstrings", "Immutability enforced", "Zero compilation errors")
        ));

        // 2. Business Logic Implementation
        tickets.add(new Ticket(
                "TICKET-2",
                "Implement Business Logic and Service Operations",
                "Implement stateful operations, validations, and algorithms according to PRD specification",
                "M",
                3,
                List.of("TICKET-1"),
                "Backend Specialist",
                List.of("All edge cases handled", "Appropriate error exceptions thrown", "Follows SOLID principles")
        ));

        // 3. Persistence or Integration Layer
        if (combined.contains("data") || combined.contains("db") || combined.contains("storage") || combined.contains("store") || combined.contains("api")) {
            tickets.add(new Ticket(
                    "TICKET-3",
                    "Data Persistence & External Integration Layer",
                    "Add database queries, repositories, or external REST/JSON connectors with retry semantics",
                    "M",
                    3,
                    List.of("TICKET-1", "TICKET-2"),
                    "Database Migration Expert",
                    List.of("Safe parameterized queries", "Connection pool management", "Error logging")
            ));
        }

        // 4. Automated Testing Suite
        List<String> testDeps = new ArrayList<>(List.of("TICKET-2"));
        if (tickets.stream().anyMatch(t -> t.id().equals("TICKET-3"))) {
            testDeps.add("TICKET-3");
        }
        tickets.add(new Ticket(
                "TICKET-4",
                "Comprehensive Automated Unit & Property-Based Test Suite",
                "Generate comprehensive JUnit 5 tests, boundary condition assertions, and mock verifications",
                "S",
                2,
                testDeps,
                "Test Specialist",
                List.of("Test coverage >= 85%", "All tests pass cleanly in Maven", "Boundary tests included")
        ));

        // 5. Security & Architecture Audit
        tickets.add(new Ticket(
                "TICKET-5",
                "Security Hardening & Code Review Audit",
                "Verify zero credential leaks, injection safety, and architectural layering rules",
                "S",
                1,
                List.of("TICKET-4"),
                "Security Reviewer",
                List.of("Passes secret leak scanning", "Passes architecture drift detector", "Approved PR")
        ));

        log.info("Decomposed spec '{}' into {} dependency-ordered tickets", specTitle, tickets.size());
        return tickets;
    }
}

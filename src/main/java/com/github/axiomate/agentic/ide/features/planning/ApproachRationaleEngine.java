package com.github.axiomate.agentic.ide.features.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature 6: "Why this approach" rationale.
 * Each plan includes rejected alternatives and the reason each lost.
 */
public class ApproachRationaleEngine {

    private static final Logger log = LoggerFactory.getLogger(ApproachRationaleEngine.class);
    private static ApproachRationaleEngine instance;

    private ApproachRationaleEngine() {}

    public static synchronized ApproachRationaleEngine getInstance() {
        if (instance == null) {
            instance = new ApproachRationaleEngine();
        }
        return instance;
    }

    public ApproachRationale generateRationale(String prompt, String targetFile) {
        String lower = prompt != null ? prompt.toLowerCase() : "";
        List<ApproachRationale.RejectedAlternative> rejected = new ArrayList<>();

        String chosenTitle;
        String chosenDesc;
        List<String> chosenBenefits = new ArrayList<>();

        if (lower.contains("concurr") || lower.contains("async") || lower.contains("thread")) {
            chosenTitle = "Lock-Free Concurrent Collections with Non-Blocking Atomic References";
            chosenDesc = "Utilize Java ConcurrentHashMap and AtomicReference for thread-safe state without heavy synchronization.";
            chosenBenefits.add("Zero contention bottlenecks on read-heavy workloads");
            chosenBenefits.add("Deadlock-free by design");
            chosenBenefits.add("Superior latency under high thread count");

            rejected.add(new ApproachRationale.RejectedAlternative(
                    "Global Coarse-Grained Synchronized Methods",
                    "Wrap entire class methods with synchronized blocks",
                    "Rejected due to severe thread serialization and thread starvation under concurrent access.",
                    "High lock contention; throughput degrades linearly with thread count."
            ));
            rejected.add(new ApproachRationale.RejectedAlternative(
                    "Asynchronous Reactive Streams (RxJava/Project Reactor)",
                    "Rewrite data pipeline into reactive Flux/Mono publishers",
                    "Rejected due to disproportionate architectural complexity, stack trace obfuscation, and unnecessary dependency overhead.",
                    "Steep learning curve, debugging difficulty, excessive memory allocation per subscription."
            ));
        } else if (lower.contains("refactor") || lower.contains("clean") || lower.contains("pattern")) {
            chosenTitle = "Modular Strategy & Adapter Pattern Decomposition";
            chosenDesc = "Extract decoupled strategy interfaces and adapters while preserving public binary interfaces.";
            chosenBenefits.add("Strict adherence to Open-Closed Principle (OCP)");
            chosenBenefits.add("Isolated unit-testability via mocking");
            chosenBenefits.add("Zero breaking changes for existing consumers");

            rejected.add(new ApproachRationale.RejectedAlternative(
                    "Monolithic Inline Switch-Case Extension",
                    "Add inline branching conditions to the existing class methods",
                    "Rejected because it violates the Single Responsibility Principle and creates regression risk in battle-tested code paths.",
                    "Cyclomatic complexity explosion; high blast radius on unrelated features."
            ));
            rejected.add(new ApproachRationale.RejectedAlternative(
                    "Complete Ground-Up Rewrite with Breaking API Change",
                    "Discard the current class and introduce a brand new API signature",
                    "Rejected because downstream callers, tests, and plugins would require simultaneous coordinated migration.",
                    "Massive blast radius, breaks backwards compatibility, high migration cost."
            ));
        } else {
            chosenTitle = "Idiomatic Java 21 Functional Composition & Immutability";
            chosenDesc = "Implement requirements leveraging records, pattern matching, and pure functions.";
            chosenBenefits.add("Guaranteed immutability and thread-safety");
            chosenBenefits.add("Concise self-documenting syntax with minimal boilerplate");
            chosenBenefits.add("Low garbage collection overhead");

            rejected.add(new ApproachRationale.RejectedAlternative(
                    "Heavy Reflection-Based Dynamic Proxying",
                    "Intercept method invocations dynamically at runtime via Java reflection",
                    "Rejected due to runtime performance penalty, loss of compile-time type safety, and GraalVM native image incompatibility.",
                    "Runtime reflection overhead; opaque failure modes; fragile refactoring."
            ));
            rejected.add(new ApproachRationale.RejectedAlternative(
                    "Stateful Mutable Singleton with Global Getters/Setters",
                    "Store state in a globally accessible mutable shared object",
                    "Rejected because shared mutable state causes unpredictable race conditions and makes unit testing difficult.",
                    "Hidden side-effects across test runs; tight coupling."
            ));
        }

        return new ApproachRationale(chosenTitle, chosenDesc, chosenBenefits, rejected);
    }
}

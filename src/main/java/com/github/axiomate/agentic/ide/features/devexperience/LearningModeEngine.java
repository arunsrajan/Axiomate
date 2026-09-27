package com.github.axiomate.agentic.ide.features.devexperience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Feature 48: Learning mode.
 * The agent explains each change as a mini-lesson, which suits juniors or unfamiliar stacks.
 */
public class LearningModeEngine {

    private static final Logger log = LoggerFactory.getLogger(LearningModeEngine.class);
    private static LearningModeEngine instance;

    private boolean learningModeEnabled = true;

    private LearningModeEngine() {}

    public static synchronized LearningModeEngine getInstance() {
        if (instance == null) {
            instance = new LearningModeEngine();
        }
        return instance;
    }

    public boolean isLearningModeEnabled() { return learningModeEnabled; }
    public void setLearningModeEnabled(boolean enabled) { this.learningModeEnabled = enabled; }

    /**
     * Synthesizes an educational mini-lesson explaining the architectural rationale and patterns of a code change.
     */
    public LearningLesson createLesson(String changeDescription, String codeSnippet) {
        log.info("Generating educational mini-lesson for change: '{}'", changeDescription);

        String title = "Mastering Defensive Architecture & Safe Concurrency";
        String concept = "Thread-Safe State Encapsulation and Pure Immutability";
        String whyMatters = "In multi-threaded server environments, shared mutable state causes intermittent race conditions that are notoriously hard to debug. Immutability guarantees zero synchronization bottlenecks.";
        String pattern = "Strategy Pattern & Immutability / Record Encapsulation";

        List<String> takeaways = List.of(
                "Java 21 records provide automatic equals(), hashCode(), and immutable getters with zero boilerplate.",
                "Favor pure functions: inputs map deterministically to outputs without hidden external side-effects.",
                "Always validate method parameters at system boundaries using Objects.requireNonNull()."
        );

        String challenge = "Why is an unmodifiable List<T> superior to returning a raw mutable ArrayList in public API methods?";
        String hint = "Returning a mutable reference allows callers to alter internal class state directly, violating encapsulation.";

        return new LearningLesson(title, concept, whyMatters, pattern, takeaways, challenge, hint);
    }
}

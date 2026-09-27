package com.github.axiomate.agentic.ide.features.planning;

import java.util.List;

/**
 * Represents a single ambiguity, assumption, or clarification item before task execution.
 */
public record ClarificationQuestion(
        String id,
        String category,
        String question,
        String assumedDefault,
        List<String> options,
        String resolvedAnswer
) {
    public ClarificationQuestion withResolution(String answer) {
        return new ClarificationQuestion(id, category, question, assumedDefault, options, answer != null ? answer : assumedDefault);
    }

    public boolean isResolved() {
        return resolvedAnswer != null && !resolvedAnswer.isBlank();
    }
}

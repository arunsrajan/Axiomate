package com.github.axiomate.agentic.ide.features.devexperience;

import java.util.List;

/**
 * Model representing an interactive educational mini-lesson generated for code changes.
 */
public record LearningLesson(
        String lessonTitle,
        String coreConcept,
        String whyThisMatters,
        String designPatternExplained,
        List<String> keyTakeaways,
        String interactiveChallengeQuestion,
        String expectedAnswerHint
) {
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 🎓 Learning Mode: ").append(lessonTitle).append("\n\n");
        sb.append("**Concept**: ").append(coreConcept).append("\n\n");
        sb.append("**Why this matters**: ").append(whyThisMatters).append("\n\n");
        if (designPatternExplained != null && !designPatternExplained.isBlank()) {
            sb.append("**Pattern Spotlight**: ").append(designPatternExplained).append("\n\n");
        }
        sb.append("**Key Takeaways**:\n");
        for (String k : keyTakeaways) sb.append("- ").append(k).append("\n");

        sb.append("\n**🧠 Test Your Knowledge**:\n");
        sb.append("> ").append(interactiveChallengeQuestion).append("\n\n");
        sb.append("*(Hint: ").append(expectedAnswerHint).append(")*\n");
        return sb.toString();
    }
}

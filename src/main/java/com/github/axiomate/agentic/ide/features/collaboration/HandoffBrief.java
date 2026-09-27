package com.github.axiomate.agentic.ide.features.collaboration;

import java.time.Instant;
import java.util.List;

/**
 * Structured transition brief model when handing off tasks between team members or agents.
 */
public record HandoffBrief(
        String handoffId,
        Instant generatedAt,
        String originalAssignee,
        String targetAssignee,
        String taskTitle,
        String currentStatus,
        List<String> workCompletedSoFar,
        List<String> keyDecisionsMade,
        List<String> filesModified,
        List<String> openQuestionsAndRisks,
        List<String> recommendedImmediateNextSteps
) {
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🤝 Agent Handoff Brief: ").append(taskTitle).append("\n\n");
        sb.append("- **Original Assignee**: ").append(originalAssignee).append("\n");
        sb.append("- **Target Assignee**: ").append(targetAssignee).append("\n");
        sb.append("- **Status**: ").append(currentStatus).append("\n\n");

        sb.append("### ✅ Work Completed So Far\n");
        for (String w : workCompletedSoFar) sb.append("- ").append(w).append("\n");

        sb.append("\n### ⚖ Key Architectural Decisions\n");
        for (String d : keyDecisionsMade) sb.append("- ").append(d).append("\n");

        sb.append("\n### 📂 Files Touched\n");
        for (String f : filesModified) sb.append("- `").append(f).append("`\n");

        sb.append("\n### ❓ Open Questions & Risks\n");
        for (String q : openQuestionsAndRisks) sb.append("- ").append(q).append("\n");

        sb.append("\n### 🚀 Recommended Immediate Next Steps\n");
        for (String s : recommendedImmediateNextSteps) sb.append("- ").append(s).append("\n");

        return sb.toString();
    }
}

package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;

/**
 * Renders an agent session as a readable Markdown transcript that can be shared, committed,
 * or pasted into another coding agent as hand-off context.
 */
public final class SessionTranscriptExporter {

    private static final int TOOL_PREVIEW_CHARS = 4_000;

    private SessionTranscriptExporter() {
    }

    public static String toMarkdown(AgentSession session, boolean includeToolOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(session.getName()).append("\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| Provider / model | `").append(session.getProviderId()).append("` / `").append(session.getModelId()).append("` |\n");
        sb.append("| Created | ").append(session.getCreatedAt()).append(" |\n");
        sb.append("| Last active | ").append(session.getUpdatedAt()).append(" |\n");
        sb.append("| Messages | ").append(session.getMessages().size()).append(" |\n");
        if (!session.getOrigin().isBlank()) {
            sb.append("| Origin | ").append(session.getOrigin().replace("|", "\\|")).append(" |\n");
        }
        sb.append('\n');

        for (AgentMessage m : session.getMessages()) {
            String time = m.getTimestamp() != null ? " · " + m.getTimestamp() : "";
            switch (m.getRole()) {
                case USER -> sb.append("## 🧑 User").append(time).append("\n\n").append(m.getContent().strip()).append("\n\n");
                case ASSISTANT -> sb.append("## 🤖 Assistant").append(time).append("\n\n").append(m.getContent().strip()).append("\n\n");
                case SYSTEM -> sb.append("> **System:** ").append(m.getContent().strip().replace("\n", "\n> ")).append("\n\n");
                case THINKING -> details(sb, "🧠 Reasoning", m.getContent(), null);
                case TOOL_CALL -> details(sb, "🔧 Tool call: " + nameOf(m), m.getContent(), "json");
                case TOOL -> {
                    if (includeToolOutput) details(sb, "📥 Tool result: " + nameOf(m), m.getContent(), "text");
                }
            }
        }
        return sb.toString();
    }

    private static String nameOf(AgentMessage m) {
        return m.getToolName() != null ? m.getToolName() : "tool";
    }

    private static void details(StringBuilder sb, String summary, String body, String lang) {
        String content = body == null ? "" : body.strip();
        if (content.length() > TOOL_PREVIEW_CHARS) {
            content = content.substring(0, TOOL_PREVIEW_CHARS) + "\n… (truncated)";
        }
        sb.append("<details><summary>").append(summary).append("</summary>\n\n");
        if (lang != null) {
            String fence = content.contains("```") ? "~~~~" : "```";
            sb.append(fence).append(lang).append('\n').append(content).append('\n').append(fence).append("\n");
        } else {
            sb.append(content).append('\n');
        }
        sb.append("\n</details>\n\n");
    }
}

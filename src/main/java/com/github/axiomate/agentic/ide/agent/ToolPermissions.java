package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the agent may run a tool call under its session's {@link ExecutionMode}: Plan allows read-only
 * calls only, Manual asks the user (through the {@link ApprovalHandler} the IDE installs), Auto allows everything.
 */
public final class ToolPermissions {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissions.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Decision {ALLOW_ONCE, ALLOW_ALWAYS, DENY}

    /** The user's answer to an approval request; {@code reason} is passed to the agent when denying. */
    public record Answer(Decision decision, String reason) {
        public static Answer allowOnce() {
            return new Answer(Decision.ALLOW_ONCE, null);
        }

        public static Answer allowAlways() {
            return new Answer(Decision.ALLOW_ALWAYS, null);
        }

        public static Answer deny(String reason) {
            return new Answer(Decision.DENY, reason);
        }
    }

    /** A tool call waiting for the user's approval in Manual mode. */
    public record Request(String sessionName, String toolName, String arguments, boolean readOnly) {
    }

    /** Asks the user; called on the agent's thread and may block until they answer. */
    public interface ApprovalHandler {
        Answer ask(Request request);
    }

    /**
     * @param refusal what the agent is told instead of the tool's output when the call is not allowed
     */
    public record Verdict(boolean allowed, String refusal) {
        static Verdict allow() {
            return new Verdict(true, null);
        }

        static Verdict refuse(String message) {
            return new Verdict(false, message);
        }
    }

    /** Tools that never change files, run commands or call out: allowed in every mode. */
    private static final Set<String> READ_ONLY_TOOLS = Set.of("search_files", "view_image", "task_list");

    private static volatile ApprovalHandler approvalHandler;

    private ToolPermissions() {
    }

    public static void setApprovalHandler(ApprovalHandler handler) {
        approvalHandler = handler;
    }

    /**
     * True for calls that only read: file_system read/list, code_editor read_file, agent_memory search/recall/list,
     * search_files, view_image and task_list (which only keeps the session's plan). Shells, edits, refactors and MCP
     * tools are not, since what they do cannot be known in advance.
     */
    public static boolean isReadOnly(String toolName, String arguments) {
        if (toolName == null) return false;
        String tool = toolName.toLowerCase(Locale.ROOT);
        if (READ_ONLY_TOOLS.contains(tool)) return true;
        String action = action(arguments);
        return switch (tool) {
            case "file_system" -> action == null || action.equals("read") || action.equals("list");
            case "code_editor" -> "read_file".equals(action);
            case "agent_memory" -> action == null || Set.of("search", "recall", "list").contains(action);
            default -> false;
        };
    }

    /** True when {@link #check} will ask the user about this call (Manual mode, not yet always-allowed). */
    public static boolean wouldAsk(AgentSession session, String toolName, String arguments) {
        if (session == null || session.getExecutionMode() != ExecutionMode.MANUAL || approvalHandler == null) return false;
        if (isReadOnly(toolName, arguments) && !ConfigManager.getInstance().getConfig().isManualModeAsksForReadOnlyTools()) {
            return false;
        }
        return !session.getAllowedTools().contains(toolName);
    }

    /** Whether {@code session} may run this call now; in Manual mode this may ask the user and wait. */
    public static Verdict check(AgentSession session, String toolName, String arguments) {
        ExecutionMode mode = session != null ? session.getExecutionMode() : ExecutionMode.AUTO;
        boolean readOnly = isReadOnly(toolName, arguments);
        switch (mode) {
            case AUTO -> {
                return Verdict.allow();
            }
            case PLAN -> {
                if (readOnly) return Verdict.allow();
                log.info("Plan mode: refused {} for session '{}'", toolName, session.getName());
                return Verdict.refuse("BLOCKED: Plan mode is on, so " + toolName + " was not run: it can change files "
                        + "or run commands. Keep investigating with read-only tools (file_system read/list, code_editor "
                        + "read_file, search_files, view_image, agent_memory search) and finish with your implementation "
                        + "plan as a numbered list. The user switches to Manual or Auto mode to carry it out.");
            }
            default -> {
                if (readOnly && !ConfigManager.getInstance().getConfig().isManualModeAsksForReadOnlyTools()) {
                    return Verdict.allow();
                }
                if (session.getAllowedTools().contains(toolName)) return Verdict.allow();
                ApprovalHandler handler = approvalHandler;
                if (handler == null) {
                    return Verdict.refuse("DENIED: Manual mode needs the user to approve " + toolName
                            + ", but no one is available to approve it. Tell the user what you wanted to run.");
                }
                Answer answer;
                try {
                    answer = handler.ask(new Request(session.getName(), toolName, arguments, readOnly));
                } catch (RuntimeException e) {
                    log.warn("Tool approval failed: {}", e.getMessage());
                    answer = Answer.deny("the approval prompt failed");
                }
                if (answer == null || answer.decision() == Decision.DENY) {
                    String reason = answer != null && answer.reason() != null && !answer.reason().isBlank()
                            ? " Reason: " + answer.reason().strip() + "." : "";
                    log.info("Manual mode: the user denied {}", toolName);
                    return Verdict.refuse("DENIED: The user did not allow " + toolName + " to run." + reason
                            + " Do not retry the same call; change your approach or ask the user.");
                }
                if (answer.decision() == Decision.ALLOW_ALWAYS && !session.getAllowedTools().contains(toolName)) {
                    session.getAllowedTools().add(toolName);
                    com.github.axiomate.agentic.ide.agent.session.SessionManager.getInstance().autoSaveCurrentProjectSessions();
                }
                return Verdict.allow();
            }
        }
    }

    private static String action(String arguments) {
        if (arguments == null || !arguments.trim().startsWith("{")) return null;
        try {
            JsonNode node = MAPPER.readTree(arguments);
            String a = node.path("action").asText("");
            return a.isEmpty() ? null : a.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}

package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.AgentTaskContext;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionTask;
import com.github.axiomate.agentic.ide.agent.session.TaskBoard;

import java.util.ArrayList;
import java.util.List;

/**
 * The session's task list, shown in the Tasks panel: the agent writes its plan as steps and marks them as it goes.
 */
public class TaskListTool implements AgentTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "task_list";
    }

    @Override
    public String getDescription() {
        return """
            task_list: Keep the session's task list (shown to the user in the Tasks panel). For work with several
            steps, write the steps first, mark one in_progress while you work on it and completed when it is done.
            Arguments JSON schema (status: "pending" | "in_progress" | "completed"):
            { "action": "set", "tasks": [ { "title": "Add the field", "status": "pending" }, ... ] }   replaces the list
            { "action": "add", "title": "Write the test" }
            { "action": "update", "index": 2, "status": "completed" }   index is 1-based; "title" renames
            { "action": "list" }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        AgentSession session = AgentTaskContext.session();
        if (session == null) return "ERROR: No active session.";
        String trimmed = arguments == null ? "" : arguments.trim();
        JsonNode json = trimmed.startsWith("{") ? mapper.readTree(trimmed) : mapper.createObjectNode();
        String action = json.path("action").asText(json.has("tasks") ? "set" : "list").toLowerCase();
        TaskBoard board = TaskBoard.getInstance();

        switch (action) {
            case "set", "replace", "write" -> {
                if (!json.path("tasks").isArray()) return "ERROR: 'set' needs a \"tasks\" array.";
                List<SessionTask> tasks = new ArrayList<>();
                for (JsonNode t : json.path("tasks")) {
                    String title = t.isTextual() ? t.asText() : t.path("title").asText(t.path("content").asText(""));
                    SessionTask.Status status = SessionTask.Status.parse(t.path("status").asText("pending"));
                    tasks.add(new SessionTask(title, status != null ? status : SessionTask.Status.PENDING, "agent"));
                }
                board.replace(session, tasks);
            }
            case "add" -> {
                if (board.add(session, json.path("title").asText(""), "agent") == null) {
                    return "ERROR: 'add' needs a non-empty \"title\" (and at most 50 tasks).";
                }
            }
            case "update", "complete", "start" -> {
                int index = json.path("index").asInt(-1);
                List<SessionTask> tasks = session.getTasks();
                if (index < 1 || index > tasks.size()) {
                    return "ERROR: 'index' must be between 1 and " + tasks.size() + ".\n" + TaskBoard.describe(tasks);
                }
                SessionTask task = tasks.get(index - 1);
                SessionTask.Status status = action.equals("complete") ? SessionTask.Status.COMPLETED
                        : action.equals("start") ? SessionTask.Status.IN_PROGRESS
                        : SessionTask.Status.parse(json.path("status").asText(""));
                if (status == null && !json.has("title")) {
                    return "ERROR: 'update' needs \"status\" (pending, in_progress or completed) or \"title\".";
                }
                if (status != null) board.setStatus(session, task.getId(), status);
                if (json.has("title")) board.rename(session, task.getId(), json.path("title").asText());
            }
            case "list" -> {
            }
            default -> {
                return "ERROR: Unknown action '" + action + "'. Use set, add, update or list.";
            }
        }
        int[] p = TaskBoard.progress(session);
        return "Task list (" + p[0] + "/" + p[1] + " done):\n" + TaskBoard.describe(session.getTasks());
    }
}

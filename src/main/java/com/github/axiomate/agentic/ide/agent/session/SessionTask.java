package com.github.axiomate.agentic.ide.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * One step of a session's task list, kept by the agent (task_list tool) or added by the user in the Tasks panel.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionTask {

    public enum Status {
        PENDING, IN_PROGRESS, COMPLETED;

        /** "pending", "in_progress", "in progress", "done", "completed"… or null. */
        public static Status parse(String text) {
            if (text == null) return null;
            String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_').replace('-', '_');
            return switch (t) {
                case "pending", "todo", "to_do", "open", "not_started" -> PENDING;
                case "in_progress", "inprogress", "active", "doing", "started", "running" -> IN_PROGRESS;
                case "completed", "complete", "done", "finished" -> COMPLETED;
                default -> null;
            };
        }
    }

    private String id = UUID.randomUUID().toString();
    private String title = "";
    private Status status = Status.PENDING;
    /** "agent" or "user". */
    private String source = "user";

    public SessionTask() {
    }

    public SessionTask(String title, Status status, String source) {
        this.title = title != null ? title.strip() : "";
        this.status = status != null ? status : Status.PENDING;
        this.source = source;
    }

    public SessionTask copy() {
        SessionTask t = new SessionTask(title, status, source);
        t.id = id;
        return t;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title != null ? title.strip() : "";
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status != null ? status : Status.PENDING;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isDone() {
        return status == Status.COMPLETED;
    }
}

package com.github.axiomate.agentic.ide.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.axiomate.agentic.ide.agent.AgentMessage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Represents an autonomous Agent Session with dedicated model parameters,
 * conversation history, and token consumption tracking.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentSession {

    private String id;
    private String name;
    private String providerId;
    private String modelId;
    private boolean autoRoutingEnabled = false;
    private String systemPrompt = "";
    private List<AgentMessage> messages = new CopyOnWriteArrayList<>();
    private TokenTracker tokenTracker;
    private String createdAt;
    private String updatedAt;
    private boolean pinned = false;
    private String origin = "";
    /** Folder the session works in; the Explorer switches to it when the session is selected. */
    private String projectPath;
    /** Plan / Manual / Auto. Sessions saved by older versions have none and run in Auto, as they always did. */
    private com.github.axiomate.agentic.ide.agent.ExecutionMode executionMode = com.github.axiomate.agentic.ide.agent.ExecutionMode.AUTO;
    /** Tools the user chose "Always allow" for in Manual mode. */
    private List<String> allowedTools = new CopyOnWriteArrayList<>();
    /** The session's task list (Tasks panel), kept by the agent's task_list tool and by the user. */
    private List<SessionTask> tasks = new CopyOnWriteArrayList<>();

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AgentSession() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        this.updatedAt = this.createdAt;
        this.tokenTracker = new TokenTracker(128_000);
    }

    public AgentSession(String name, String providerId, String modelId, int maxContextTokens) {
        this(name, providerId, modelId, false, maxContextTokens);
    }

    public AgentSession(String name, String providerId, String modelId, boolean autoRoutingEnabled, int maxContextTokens) {
        this();
        this.name = name;
        this.providerId = providerId;
        this.modelId = modelId;
        this.autoRoutingEnabled = autoRoutingEnabled;
        this.tokenTracker = new TokenTracker(maxContextTokens);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProviderId() {
        return providerId != null ? providerId : "MOCK";
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getModelId() {
        return modelId != null ? modelId : "mock-agent";
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentMessage> messages) {
        this.messages = new CopyOnWriteArrayList<>(messages);
    }

    public void addMessage(AgentMessage message) {
        this.messages.add(message);
        touch();
        long est = TokenTracker.estimateTokens(message.getContent());
        if (message.isUser()) {
            this.tokenTracker.recordUsage(est, 0);
        } else if (message.isAssistant()) {
            this.tokenTracker.recordUsage(0, est);
        } else {
            this.tokenTracker.recordUsage(est, 0);
        }
    }

    public void clearMessages() {
        this.messages.clear();
        this.tokenTracker.reset();
        touch();
    }

    /**
     * Marks the session as recently active.
     */
    public void touch() {
        this.updatedAt = LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    /**
     * Creates an independent copy of this session (new id, copied history and settings).
     */
    public AgentSession fork(String newName) {
        return forkUpTo(messages.size(), newName);
    }

    /**
     * A copy holding only the first {@code endExclusive} messages: "fork from here" continues the conversation from
     * that point. Messages keep their images and interim flag (interim narration must stay out of later requests).
     */
    public AgentSession forkUpTo(int endExclusive, String newName) {
        AgentSession copy = new AgentSession(newName, providerId, modelId, autoRoutingEnabled,
                tokenTracker != null ? tokenTracker.getMaxContextTokens() : 128_000);
        copy.setSystemPrompt(systemPrompt);
        copy.setOrigin("Forked from '" + name + "'");
        copy.setExecutionMode(executionMode);
        List<AgentMessage> snapshot = List.copyOf(messages);
        int end = Math.max(0, Math.min(endExclusive, snapshot.size()));
        List<AgentMessage> kept = new java.util.ArrayList<>();
        for (int i = 0; i < end; i++) kept.add(snapshot.get(i).copy());
        copy.setMessages(kept);
        for (SessionTask t : tasks) copy.tasks.add(t.copy());
        if (tokenTracker != null && copy.tokenTracker != null) {
            long estimate = 0;
            for (AgentMessage m : kept) estimate += TokenTracker.estimateTokens(m.getContent());
            copy.tokenTracker.setEstimatedUsage(estimate);
        }
        return copy;
    }

    /** Index of the {@code ordinal}-th (0-based) user prompt in {@code messages}, or -1. */
    public static int indexOfUserMessage(List<AgentMessage> messages, int ordinal) {
        int seen = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isUser() && seen++ == ordinal) return i;
        }
        return -1;
    }

    /**
     * End (exclusive) of the turn that the {@code ordinal}-th user prompt started: the next prompt's index, or the
     * end of the conversation. {@code ordinal} -1 is the part before the first prompt.
     */
    public static int endOfTurn(List<AgentMessage> messages, int ordinal) {
        int next = indexOfUserMessage(messages, ordinal + 1);
        return next >= 0 ? next : messages.size();
    }

    public com.github.axiomate.agentic.ide.agent.ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(com.github.axiomate.agentic.ide.agent.ExecutionMode executionMode) {
        this.executionMode = executionMode != null ? executionMode : com.github.axiomate.agentic.ide.agent.ExecutionMode.AUTO;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = new CopyOnWriteArrayList<>(allowedTools != null ? allowedTools : List.of());
    }

    public List<SessionTask> getTasks() {
        return tasks;
    }

    public void setTasks(List<SessionTask> tasks) {
        this.tasks = new CopyOnWriteArrayList<>(tasks != null ? tasks : List.of());
    }

    public TokenTracker getTokenTracker() {
        return tokenTracker;
    }

    public void setTokenTracker(TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return (updatedAt == null || updatedAt.isBlank()) ? createdAt : updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getOrigin() {
        return origin != null ? origin : "";
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public boolean isAutoRoutingEnabled() {
        return autoRoutingEnabled;
    }

    public void setAutoRoutingEnabled(boolean autoRoutingEnabled) {
        this.autoRoutingEnabled = autoRoutingEnabled;
    }

    @Override
    public String toString() {
        return name + " [" + providerId + " / " + modelId + (autoRoutingEnabled ? " (Auto-Route)" : "") + "]";
    }
}


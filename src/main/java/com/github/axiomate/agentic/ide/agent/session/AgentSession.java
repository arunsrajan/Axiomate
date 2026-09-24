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
    private String systemPrompt = "";
    private List<AgentMessage> messages = new CopyOnWriteArrayList<>();
    private TokenTracker tokenTracker;
    private String createdAt;

    public AgentSession() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.tokenTracker = new TokenTracker(128_000);
    }

    public AgentSession(String name, String providerId, String modelId, int maxContextTokens) {
        this();
        this.name = name;
        this.providerId = providerId;
        this.modelId = modelId;
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

    @Override
    public String toString() {
        return name + " [" + providerId + " / " + modelId + "]";
    }
}


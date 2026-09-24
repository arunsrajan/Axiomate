package com.github.axiomate.agentic.ide.agent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Chat message model for Axiomate AI conversation.
 */
public class AgentMessage {

    private final AgentRole role;
    private final String content;
    private final String toolName;
    private final String timestamp;

    public AgentMessage(AgentRole role, String content) {
        this(role, content, null);
    }

    public AgentMessage(AgentRole role, String content, String toolName) {
        this.role = role;
        this.content = content;
        this.toolName = toolName;
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public AgentRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolName() {
        return toolName;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public boolean isUser() {
        return role == AgentRole.USER;
    }

    public boolean isAssistant() {
        return role == AgentRole.ASSISTANT;
    }

    public boolean isTool() {
        return role == AgentRole.TOOL;
    }

    public boolean isSystem() {
        return role == AgentRole.SYSTEM;
    }
}


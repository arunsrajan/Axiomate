package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Chat message model for Axiomate AI conversation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMessage {

    private AgentRole role = AgentRole.USER;
    private String content = "";
    private String toolName;
    private String timestamp;

    public AgentMessage() {
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    @JsonCreator
    public AgentMessage(
            @JsonProperty("role") AgentRole role,
            @JsonProperty("content") String content,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("timestamp") String timestamp) {
        this.role = role != null ? role : AgentRole.USER;
        this.content = content != null ? content : "";
        this.toolName = toolName;
        this.timestamp = (timestamp != null && !timestamp.isBlank())
                ? timestamp
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public AgentMessage(AgentRole role, String content) {
        this(role, content, null, null);
    }

    public AgentMessage(AgentRole role, String content, String toolName) {
        this(role, content, toolName, null);
    }

    public AgentRole getRole() {
        return role;
    }

    public void setRole(AgentRole role) {
        this.role = role;
    }

    public String getContent() {
        return content != null ? content : "";
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    @JsonIgnore
    public boolean isUser() {
        return role == AgentRole.USER;
    }

    @JsonIgnore
    public boolean isAssistant() {
        return role == AgentRole.ASSISTANT;
    }

    @JsonIgnore
    public boolean isTool() {
        return role == AgentRole.TOOL;
    }

    @JsonIgnore
    public boolean isToolCall() {
        return role == AgentRole.TOOL_CALL;
    }

    @JsonIgnore
    public boolean isThinking() {
        return role == AgentRole.THINKING;
    }

    @JsonIgnore
    public boolean isSystem() {
        return role == AgentRole.SYSTEM;
    }
}


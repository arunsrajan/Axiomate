package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the persisted state of a project directory, including open editor tabs,
 * active tab, access timestamps, and multiple AI Agent sessions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectState {

    private String projectPath = "";
    private String projectName = "";
    private List<String> openFiles = new ArrayList<>();
    private String activeFile = "";
    private List<AgentSession> sessions = new ArrayList<>();
    private String activeSessionId = "";
    private long lastOpenedTime;
    private long lastClosedTime;

    public ProjectState() {
        this.lastOpenedTime = System.currentTimeMillis();
    }

    public ProjectState(String projectPath) {
        this();
        this.projectPath = projectPath;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public List<String> getOpenFiles() {
        return openFiles != null ? openFiles : new ArrayList<>();
    }

    public void setOpenFiles(List<String> openFiles) {
        this.openFiles = openFiles != null ? new ArrayList<>(openFiles) : new ArrayList<>();
    }

    public String getActiveFile() {
        return activeFile;
    }

    public void setActiveFile(String activeFile) {
        this.activeFile = activeFile;
    }

    public List<AgentSession> getSessions() {
        return sessions != null ? sessions : new ArrayList<>();
    }

    public void setSessions(List<AgentSession> sessions) {
        this.sessions = sessions != null ? new ArrayList<>(sessions) : new ArrayList<>();
    }

    public String getActiveSessionId() {
        return activeSessionId != null ? activeSessionId : "";
    }

    public void setActiveSessionId(String activeSessionId) {
        this.activeSessionId = activeSessionId != null ? activeSessionId : "";
    }

    public long getLastOpenedTime() {
        return lastOpenedTime;
    }

    public void setLastOpenedTime(long lastOpenedTime) {
        this.lastOpenedTime = lastOpenedTime;
    }

    public long getLastClosedTime() {
        return lastClosedTime;
    }

    public void setLastClosedTime(long lastClosedTime) {
        this.lastClosedTime = lastClosedTime;
    }
}



package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the persisted state of a project directory, including open editor tabs,
 * active tab, and access timestamps.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectState {

    private String projectPath = "";
    private String projectName = "";
    private List<String> openFiles = new ArrayList<>();
    private String activeFile = "";
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


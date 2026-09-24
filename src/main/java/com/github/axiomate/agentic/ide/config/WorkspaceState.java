package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encapsulates the overall workspace state, tracking the last opened project directory
 * and per-project editor state mappings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceState {

    private String lastOpenProjectPath = "";
    private Map<String, ProjectState> projects = new LinkedHashMap<>();

    public WorkspaceState() {
    }

    public String getLastOpenProjectPath() {
        return lastOpenProjectPath != null ? lastOpenProjectPath : "";
    }

    public void setLastOpenProjectPath(String lastOpenProjectPath) {
        this.lastOpenProjectPath = lastOpenProjectPath != null ? lastOpenProjectPath : "";
    }

    public Map<String, ProjectState> getProjects() {
        return projects != null ? projects : new LinkedHashMap<>();
    }

    public void setProjects(Map<String, ProjectState> projects) {
        this.projects = projects != null ? projects : new LinkedHashMap<>();
    }
}


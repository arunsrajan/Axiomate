package com.github.agentforge.agentic.ide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages loading and persisting project states (open editor tabs, active file, timestamps)
 * to ~/.agentforge-ide/project_state.json.
 */
public class ProjectStateManager {

    private static final Logger log = LoggerFactory.getLogger(ProjectStateManager.class);
    public static final String STATE_FILE_NAME = "project_state.json";

    private static ProjectStateManager instance;

    private final ObjectMapper objectMapper;
    private final Path stateFilePath;
    private WorkspaceState workspaceState;

    public ProjectStateManager() {
        this(ConfigManager.getAppDirectory().resolve(STATE_FILE_NAME));
    }

    public ProjectStateManager(Path stateFilePath) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.stateFilePath = stateFilePath;
        this.workspaceState = loadWorkspaceState();
    }

    public static synchronized ProjectStateManager getInstance() {
        if (instance == null) {
            instance = new ProjectStateManager();
        }
        return instance;
    }

    public synchronized WorkspaceState loadWorkspaceState() {
        if (Files.exists(stateFilePath)) {
            try {
                WorkspaceState state = objectMapper.readValue(stateFilePath.toFile(), WorkspaceState.class);
                log.info("Loaded workspace project state from {}", stateFilePath);
                return state;
            } catch (Exception e) {
                log.error("Failed to read workspace state from {}, creating default", stateFilePath, e);
            }
        }
        WorkspaceState state = new WorkspaceState();
        saveWorkspaceState(state);
        return state;
    }

    public synchronized void saveWorkspaceState(WorkspaceState state) {
        this.workspaceState = state;
        try {
            if (stateFilePath.getParent() != null && !Files.exists(stateFilePath.getParent())) {
                Files.createDirectories(stateFilePath.getParent());
            }
            objectMapper.writeValue(stateFilePath.toFile(), workspaceState);
            log.info("Saved workspace project state to {}", stateFilePath);
        } catch (IOException e) {
            log.error("Failed to write workspace state to {}", stateFilePath, e);
        }
    }

    public synchronized void saveProjectState(File projectDir, List<File> openFiles, File activeFile) {
        if (projectDir == null) return;

        String normPath = normalizePath(projectDir);
        ProjectState state = workspaceState.getProjects().get(normPath);
        if (state == null) {
            state = new ProjectState(normPath);
        }

        state.setProjectName(projectDir.getName());
        state.setProjectPath(normPath);

        List<String> filePaths = new ArrayList<>();
        if (openFiles != null) {
            for (File f : openFiles) {
                if (f != null && f.exists()) {
                    filePaths.add(normalizePath(f));
                }
            }
        }
        state.setOpenFiles(filePaths);

        if (activeFile != null && activeFile.exists()) {
            state.setActiveFile(normalizePath(activeFile));
        } else {
            state.setActiveFile("");
        }

        state.setLastOpenedTime(System.currentTimeMillis());

        workspaceState.getProjects().put(normPath, state);
        workspaceState.setLastOpenProjectPath(normPath);

        saveWorkspaceState(workspaceState);
    }

    public synchronized void closeProject(File projectDir, List<File> openFiles, File activeFile) {
        if (projectDir == null) return;

        String normPath = normalizePath(projectDir);
        ProjectState state = workspaceState.getProjects().get(normPath);
        if (state == null) {
            state = new ProjectState(normPath);
        }

        state.setProjectName(projectDir.getName());
        state.setProjectPath(normPath);

        List<String> filePaths = new ArrayList<>();
        if (openFiles != null) {
            for (File f : openFiles) {
                if (f != null && f.exists()) {
                    filePaths.add(normalizePath(f));
                }
            }
        }
        state.setOpenFiles(filePaths);

        if (activeFile != null && activeFile.exists()) {
            state.setActiveFile(normalizePath(activeFile));
        } else {
            state.setActiveFile("");
        }

        state.setLastClosedTime(System.currentTimeMillis());

        workspaceState.getProjects().put(normPath, state);
        if (normPath.equalsIgnoreCase(workspaceState.getLastOpenProjectPath())) {
            workspaceState.setLastOpenProjectPath("");
        }

        saveWorkspaceState(workspaceState);
    }

    public synchronized ProjectState getProjectState(File projectDir) {
        if (projectDir == null) return null;
        String normPath = normalizePath(projectDir);
        return workspaceState.getProjects().get(normPath);
    }

    public synchronized String getLastOpenProjectPath() {
        return workspaceState.getLastOpenProjectPath();
    }

    public synchronized void setLastOpenProjectPath(String path) {
        workspaceState.setLastOpenProjectPath(path != null ? path.trim() : "");
        saveWorkspaceState(workspaceState);
    }

    public Path getStateFilePath() {
        return stateFilePath;
    }

    public static String normalizePath(File file) {
        if (file == null) return "";
        try {
            return file.getCanonicalFile().getAbsolutePath().replace('\\', '/');
        } catch (IOException e) {
            return file.getAbsolutePath().replace('\\', '/');
        }
    }
}

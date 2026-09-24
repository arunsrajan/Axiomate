package com.github.axiomate.agentic.ide.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton manager coordinating multiple concurrent Agent Sessions in the IDE,
 * supporting project-scoped persistence (save/load sessions per project) and export/import.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static SessionManager instance;

    private final ObjectMapper objectMapper;
    private final List<AgentSession> sessions = new CopyOnWriteArrayList<>();
    private AgentSession activeSession;
    private final List<Runnable> sessionChangeListeners = new ArrayList<>();
    private File currentProjectDirectory;

    private SessionManager() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        initDefaultSession();
    }

    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    private void initDefaultSession() {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        String provider = config.getActiveProviderId();
        String model = config.getActiveModelId();

        int maxCtx = 128_000;
        ProviderConfig provCfg = config.getProvider(provider);
        if (provCfg != null) {
            ModelDefinition m = provCfg.findModel(model);
            if (m != null) maxCtx = m.getMaxContextTokens();
        }

        String name = "Primary Coding Agent";
        if (currentProjectDirectory != null) {
            name = "Agent - " + currentProjectDirectory.getName();
        }

        AgentSession defaultSession = new AgentSession(name, provider, model, maxCtx);
        sessions.clear();
        sessions.add(defaultSession);
        activeSession = defaultSession;
    }

    public List<AgentSession> getSessions() {
        return new ArrayList<>(sessions);
    }

    public AgentSession getActiveSession() {
        if (activeSession == null && !sessions.isEmpty()) {
            activeSession = sessions.get(0);
        }
        return activeSession;
    }

    public synchronized AgentSession createSession(String name, String providerId, String modelId) {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        int maxCtx = 128_000;
        ProviderConfig provCfg = config.getProvider(providerId);
        if (provCfg != null) {
            ModelDefinition m = provCfg.findModel(modelId);
            if (m != null) maxCtx = m.getMaxContextTokens();
        }

        AgentSession session = new AgentSession(name, providerId, modelId, maxCtx);
        sessions.add(session);
        activeSession = session;
        log.info("Created new Agent Session: '{}' ({}:{})", name, providerId, modelId);
        notifyListeners();
        autoSaveCurrentProjectSessions();
        return session;
    }

    public synchronized void switchSession(String sessionId) {
        for (AgentSession s : sessions) {
            if (s.getId().equals(sessionId)) {
                this.activeSession = s;
                log.info("Switched to Agent Session: '{}'", s.getName());
                notifyListeners();
                autoSaveCurrentProjectSessions();
                return;
            }
        }
    }

    public synchronized void closeSession(String sessionId) {
        if (sessions.size() <= 1) {
            // Do not delete last session; simply reset it
            getActiveSession().clearMessages();
            notifyListeners();
            autoSaveCurrentProjectSessions();
            return;
        }

        sessions.removeIf(s -> s.getId().equals(sessionId));
        if (activeSession != null && activeSession.getId().equals(sessionId)) {
            activeSession = sessions.get(0);
        }
        notifyListeners();
        autoSaveCurrentProjectSessions();
    }

    public synchronized void renameSession(String sessionId, String newName) {
        for (AgentSession s : sessions) {
            if (s.getId().equals(sessionId)) {
                s.setName(newName);
                notifyListeners();
                autoSaveCurrentProjectSessions();
                return;
            }
        }
    }

    public void addSessionChangeListener(Runnable listener) {
        sessionChangeListeners.add(listener);
    }

    public void notifyListeners() {
        for (Runnable r : sessionChangeListeners) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Error notifying session listener", e);
            }
        }
    }

    public File getCurrentProjectDirectory() {
        return currentProjectDirectory;
    }

    public void setCurrentProjectDirectory(File projectDir) {
        this.currentProjectDirectory = projectDir;
    }

    /**
     * Persists all sessions of the specified project to the central ProjectStateManager
     * and optionally to a local project directory (.axiomate/sessions.json).
     */
    public synchronized void saveSessionsForProject(File projectDir) {
        if (projectDir == null) {
            projectDir = currentProjectDirectory != null ? currentProjectDirectory : ProjectManager.getInstance().getCurrentProjectDirectory();
        }
        if (projectDir == null) return;

        this.currentProjectDirectory = projectDir;
        String activeId = (activeSession != null) ? activeSession.getId() : "";
        List<AgentSession> currentSessionsList = new ArrayList<>(sessions);

        // 1. Centralized storage via ProjectStateManager
        ProjectStateManager.getInstance().saveProjectSessions(projectDir, currentSessionsList, activeId);
        log.info("Saved {} sessions for project [{}] in ProjectStateManager", currentSessionsList.size(), projectDir.getName());

        // 2. Project-local storage in .axiomate/sessions.json
        try {
            if (projectDir.exists() && projectDir.isDirectory()) {
                Path dotAxiomate = projectDir.toPath().resolve(".axiomate");
                if (!Files.exists(dotAxiomate)) {
                    Files.createDirectories(dotAxiomate);
                }
                File localFile = dotAxiomate.resolve("sessions.json").toFile();
                SessionExportData data = new SessionExportData(activeId, currentSessionsList);
                objectMapper.writeValue(localFile, data);
                log.info("Wrote project-local session state to {}", localFile);
            }
        } catch (Exception e) {
            log.warn("Could not save project-local sessions file: {}", e.getMessage());
        }
    }

    /**
     * Loads the saved sessions for the specified project.
     * Restores all agent sessions, active session selection, and notifies listeners.
     */
    public synchronized void loadSessionsForProject(File projectDir) {
        if (projectDir == null || !projectDir.exists() || !projectDir.isDirectory()) return;

        this.currentProjectDirectory = projectDir;

        // 1. Attempt to load from ProjectStateManager
        List<AgentSession> loaded = ProjectStateManager.getInstance().getProjectSessions(projectDir);
        String savedActiveId = ProjectStateManager.getInstance().getProjectActiveSessionId(projectDir);

        // 2. Fallback to project-local .axiomate/sessions.json if empty
        if (loaded.isEmpty()) {
            File localFile = projectDir.toPath().resolve(".axiomate").resolve("sessions.json").toFile();
            if (localFile.exists()) {
                try {
                    SessionExportData data = objectMapper.readValue(localFile, SessionExportData.class);
                    if (data.getSessions() != null && !data.getSessions().isEmpty()) {
                        loaded = data.getSessions();
                        savedActiveId = data.getActiveSessionId();
                        log.info("Restored {} sessions from project-local {}", loaded.size(), localFile);
                    }
                } catch (Exception e) {
                    log.warn("Failed reading project-local sessions.json: {}", e.getMessage());
                }
            }
        }

        sessions.clear();
        if (!loaded.isEmpty()) {
            sessions.addAll(loaded);
            AgentSession matched = null;
            if (savedActiveId != null && !savedActiveId.isBlank()) {
                for (AgentSession s : sessions) {
                    if (s.getId().equals(savedActiveId)) {
                        matched = s;
                        break;
                    }
                }
            }
            activeSession = (matched != null) ? matched : sessions.get(0);
            log.info("Successfully loaded {} sessions for project [{}] (active: '{}')",
                    sessions.size(), projectDir.getName(), activeSession.getName());
        } else {
            initDefaultSession();
            log.info("No saved sessions found for project [{}], initialized default session", projectDir.getName());
        }

        notifyListeners();
    }

    public synchronized void autoSaveCurrentProjectSessions() {
        File dir = currentProjectDirectory != null ? currentProjectDirectory : ProjectManager.getInstance().getCurrentProjectDirectory();
        if (dir != null) {
            saveSessionsForProject(dir);
        }
    }

    /**
     * Exports all current sessions to a standalone JSON file.
     */
    public synchronized void exportSessionsToFile(File targetFile) throws IOException {
        if (targetFile == null) return;
        if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }
        String activeId = (activeSession != null) ? activeSession.getId() : "";
        SessionExportData exportData = new SessionExportData(activeId, new ArrayList<>(sessions));
        objectMapper.writeValue(targetFile, exportData);
        log.info("Exported {} sessions to {}", sessions.size(), targetFile.getAbsolutePath());
    }

    /**
     * Imports sessions from a JSON file, replacing or appending into current session list.
     */
    public synchronized void importSessionsFromFile(File sourceFile, boolean append) throws IOException {
        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("File not found: " + sourceFile);
        }

        List<AgentSession> importedList = new ArrayList<>();
        String importedActiveId = "";

        try {
            SessionExportData data = objectMapper.readValue(sourceFile, SessionExportData.class);
            if (data.getSessions() != null) {
                importedList = data.getSessions();
                importedActiveId = data.getActiveSessionId();
            }
        } catch (Exception ex) {
            // Try fallback as List<AgentSession> directly
            importedList = objectMapper.readValue(sourceFile, new TypeReference<List<AgentSession>>() {});
        }

        if (importedList.isEmpty()) {
            throw new IOException("No valid Agent Sessions found in file");
        }

        if (!append) {
            sessions.clear();
        }
        sessions.addAll(importedList);

        if (!importedActiveId.isBlank()) {
            for (AgentSession s : sessions) {
                if (s.getId().equals(importedActiveId)) {
                    activeSession = s;
                    break;
                }
            }
        }
        if (activeSession == null || !sessions.contains(activeSession)) {
            activeSession = sessions.get(0);
        }

        log.info("Imported {} sessions from {} (append={})", importedList.size(), sourceFile.getName(), append);
        notifyListeners();
        autoSaveCurrentProjectSessions();
    }

    /**
     * Encapsulates exported session data with active session pointer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionExportData {
        private String activeSessionId = "";
        private List<AgentSession> sessions = new ArrayList<>();

        public SessionExportData() {}

        public SessionExportData(String activeSessionId, List<AgentSession> sessions) {
            this.activeSessionId = activeSessionId != null ? activeSessionId : "";
            this.sessions = sessions != null ? sessions : new ArrayList<>();
        }

        public String getActiveSessionId() {
            return activeSessionId;
        }

        public void setActiveSessionId(String activeSessionId) {
            this.activeSessionId = activeSessionId;
        }

        public List<AgentSession> getSessions() {
            return sessions;
        }

        public void setSessions(List<AgentSession> sessions) {
            this.sessions = sessions;
        }
    }
}



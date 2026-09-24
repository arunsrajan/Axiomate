package com.github.axiomate.agentic.ide.agent.session;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton manager coordinating multiple concurrent Agent Sessions in the IDE.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static SessionManager instance;

    private final List<AgentSession> sessions = new CopyOnWriteArrayList<>();
    private AgentSession activeSession;
    private final List<Runnable> sessionChangeListeners = new ArrayList<>();

    private SessionManager() {
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

        AgentSession defaultSession = new AgentSession("Primary Coding Agent", provider, model, maxCtx);
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
        return session;
    }

    public synchronized void switchSession(String sessionId) {
        for (AgentSession s : sessions) {
            if (s.getId().equals(sessionId)) {
                this.activeSession = s;
                log.info("Switched to Agent Session: '{}'", s.getName());
                notifyListeners();
                return;
            }
        }
    }

    public synchronized void closeSession(String sessionId) {
        if (sessions.size() <= 1) {
            // Do not delete last session; simply reset it
            getActiveSession().clearMessages();
            notifyListeners();
            return;
        }

        sessions.removeIf(s -> s.getId().equals(sessionId));
        if (activeSession != null && activeSession.getId().equals(sessionId)) {
            activeSession = sessions.get(0);
        }
        notifyListeners();
    }

    public synchronized void renameSession(String sessionId, String newName) {
        for (AgentSession s : sessions) {
            if (s.getId().equals(sessionId)) {
                s.setName(newName);
                notifyListeners();
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
}


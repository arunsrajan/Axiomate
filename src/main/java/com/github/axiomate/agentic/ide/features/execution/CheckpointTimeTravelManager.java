package com.github.axiomate.agentic.ide.features.execution;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 10: Checkpoint and time-travel.
 * Every agent step is snapshotted so you can rewind to any point and fork from there.
 */
public class CheckpointTimeTravelManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointTimeTravelManager.class);
    private static CheckpointTimeTravelManager instance;

    private final Map<String, List<AgentCheckpoint>> sessionCheckpoints = new ConcurrentHashMap<>();

    private CheckpointTimeTravelManager() {}

    public static synchronized CheckpointTimeTravelManager getInstance() {
        if (instance == null) {
            instance = new CheckpointTimeTravelManager();
        }
        return instance;
    }

    /**
     * Creates a snapshot checkpoint at the current agent step.
     */
    public AgentCheckpoint createCheckpoint(String sessionId, String stepDesc, Map<String, String> fileSnapshots, String activeFile) {
        String safeSession = sessionId != null ? sessionId : "default-session";
        List<AgentCheckpoint> list = sessionCheckpoints.computeIfAbsent(safeSession, k -> new CopyOnWriteArrayList<>());

        int stepNum = list.size() + 1;
        String checkpointId = "chk-" + safeSession + "-step-" + stepNum + "-" + System.currentTimeMillis();
        String parentId = list.isEmpty() ? null : list.get(list.size() - 1).checkpointId();

        List<AgentMessage> historyCopy = new ArrayList<>();
        AgentSession sess = SessionManager.getInstance().getSessions().stream()
                .filter(s -> s.getId().equals(safeSession))
                .findFirst()
                .orElse(null);
        if (sess != null) {
            historyCopy.addAll(sess.getMessages());
        }

        AgentCheckpoint cp = new AgentCheckpoint(
                checkpointId,
                stepNum,
                stepDesc,
                Instant.now(),
                safeSession,
                historyCopy,
                fileSnapshots != null ? new HashMap<>(fileSnapshots) : new HashMap<>(),
                activeFile,
                parentId
        );

        list.add(cp);
        log.info("Created checkpoint {} for session {} (step {})", checkpointId, safeSession, stepNum);
        return cp;
    }

    public List<AgentCheckpoint> getCheckpoints(String sessionId) {
        return sessionCheckpoints.getOrDefault(sessionId, List.of());
    }

    public Optional<AgentCheckpoint> getCheckpoint(String sessionId, String checkpointId) {
        List<AgentCheckpoint> list = sessionCheckpoints.get(sessionId);
        if (list == null) return Optional.empty();
        return list.stream().filter(c -> c.checkpointId().equals(checkpointId)).findFirst();
    }

    /**
     * Rewinds session state and restores conversation history to a previous checkpoint.
     */
    public boolean rewindToCheckpoint(String sessionId, String checkpointId) {
        Optional<AgentCheckpoint> opt = getCheckpoint(sessionId, checkpointId);
        if (opt.isEmpty()) {
            log.warn("Checkpoint {} not found for session {}", checkpointId, sessionId);
            return false;
        }

        AgentCheckpoint target = opt.get();
        AgentSession sess = SessionManager.getInstance().getSessions().stream()
                .filter(s -> s.getId().equals(sessionId))
                .findFirst()
                .orElse(null);
        if (sess != null) {
            sess.getMessages().clear();
            sess.getMessages().addAll(target.conversationHistory());
            SessionManager.getInstance().notifyListeners();
            log.info("Rewound session {} to checkpoint {} (step {})", sessionId, checkpointId, target.stepNumber());
            return true;
        }
        return false;
    }

    /**
     * Forks a new timeline from an existing checkpoint into a brand new agent session.
     */
    public AgentSession forkFromCheckpoint(String sessionId, String checkpointId, String forkedSessionName) {
        Optional<AgentCheckpoint> opt = getCheckpoint(sessionId, checkpointId);
        if (opt.isEmpty()) return null;

        AgentCheckpoint target = opt.get();
        AgentSession newSession = SessionManager.getInstance().createSession(
                forkedSessionName != null ? forkedSessionName : "Fork from Step " + target.stepNumber(),
                "MOCK",
                "mock-agent"
        );

        newSession.getMessages().clear();
        newSession.getMessages().addAll(target.conversationHistory());
        SessionManager.getInstance().switchSession(newSession.getId());

        // Snapshot initial state of the new forked session
        createCheckpoint(newSession.getId(), "Forked from " + target.checkpointId(), target.fileSnapshots(), target.activeFilePath());

        log.info("Forked new session {} from checkpoint {}", newSession.getId(), checkpointId);
        return newSession;
    }
}

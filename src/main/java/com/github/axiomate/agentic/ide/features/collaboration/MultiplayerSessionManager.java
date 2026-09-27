package com.github.axiomate.agentic.ide.features.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 37: Multiplayer agent sessions.
 * Several developers steer the same agent in real time, with visible cursors and intents.
 */
public class MultiplayerSessionManager {

    private static final Logger log = LoggerFactory.getLogger(MultiplayerSessionManager.class);
    private static MultiplayerSessionManager instance;

    public interface PeerChangeListener {
        void onPeersUpdated(List<CollaborativePeer> peers);
    }

    private final Map<String, CollaborativePeer> activePeers = new ConcurrentHashMap<>();
    private final List<PeerChangeListener> listeners = new CopyOnWriteArrayList<>();

    private MultiplayerSessionManager() {
        initDefaultPeers();
    }

    public static synchronized MultiplayerSessionManager getInstance() {
        if (instance == null) {
            instance = new MultiplayerSessionManager();
        }
        return instance;
    }

    private void initDefaultPeers() {
        registerPeer(new CollaborativePeer(
                "peer-alice",
                "Alice (Tech Lead)",
                "alice@axiomate.io",
                "AIAgentPanel:PromptInput",
                "Defining architectural constraints for refactoring",
                Instant.now(),
                "#58A6FF"
        ));
        registerPeer(new CollaborativePeer(
                "peer-bob",
                "Bob (Backend Dev)",
                "bob@axiomate.io",
                "Calculator.java:L58",
                "Reviewing generated unit test coverage",
                Instant.now(),
                "#3FB950"
        ));
    }

    public void addListener(PeerChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PeerChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        List<CollaborativePeer> list = getActivePeers();
        for (PeerChangeListener l : listeners) {
            try {
                l.onPeersUpdated(list);
            } catch (Exception e) {
                log.warn("Error notifying peer change listener", e);
            }
        }
    }

    public List<CollaborativePeer> getActivePeers() {
        return new ArrayList<>(activePeers.values());
    }

    public void registerPeer(CollaborativePeer peer) {
        if (peer != null) {
            activePeers.put(peer.peerId(), peer);
            log.info("Registered multiplayer peer: {}", peer.displayName());
            notifyListeners();
        }
    }

    public void updatePeerIntent(String peerId, String newIntent, String newCursor) {
        CollaborativePeer existing = activePeers.get(peerId);
        if (existing != null) {
            CollaborativePeer updated = new CollaborativePeer(
                    existing.peerId(),
                    existing.displayName(),
                    existing.email(),
                    newCursor != null ? newCursor : existing.cursorPosition(),
                    newIntent != null ? newIntent : existing.activeIntent(),
                    Instant.now(),
                    existing.colorHex()
            );
            activePeers.put(peerId, updated);
            notifyListeners();
        }
    }

    public void removePeer(String peerId) {
        if (peerId != null) {
            activePeers.remove(peerId);
            notifyListeners();
        }
    }
}

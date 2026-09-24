package com.github.axiomate.agentic.ide.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleton manager coordinating Agentic AI Memory across the IDE.
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static MemoryManager instance;

    private final AgentMemoryStore memoryStore;
    private final List<Runnable> changeListeners = new ArrayList<>();

    private MemoryManager() {
        this.memoryStore = new JsonAgentMemoryStore();
    }

    public static synchronized MemoryManager getInstance() {
        if (instance == null) {
            instance = new MemoryManager();
        }
        return instance;
    }

    public AgentMemoryStore getMemoryStore() {
        return memoryStore;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public void notifyChanged() {
        for (Runnable r : changeListeners) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Error notifying memory change listener", e);
            }
        }
    }

    public void addMemory(MemoryItem item) {
        memoryStore.addMemory(item);
        notifyChanged();
    }

    public void removeMemory(String id) {
        memoryStore.removeMemory(id);
        notifyChanged();
    }

    public int importAllMemories(File source) throws IOException {
        int count = memoryStore.importAllMemories(source);
        notifyChanged();
        return count;
    }

    public void exportAllMemories(File target) throws IOException {
        memoryStore.exportAllMemories(target);
    }

    public void clear() {
        memoryStore.clear();
        notifyChanged();
    }

    /**
     * Record an episodic memory of a completed agent interaction.
     */
    public void recordEpisode(String prompt, String summaryOutcome) {
        String title = prompt.length() > 50 ? prompt.substring(0, 47) + "..." : prompt;
        MemoryItem item = new MemoryItem(
                MemoryType.EPISODIC,
                "Task: " + title,
                "Prompt: " + prompt + "\nOutcome: " + summaryOutcome,
                List.of("episode", "task-history")
        );
        addMemory(item);
    }
}


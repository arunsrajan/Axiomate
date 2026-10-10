package com.github.axiomate.agentic.ide.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton manager coordinating Agentic AI Memory across the IDE.
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    private static MemoryManager instance;

    private final AgentMemoryStore memoryStore;
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

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

    public void addMemories(Collection<MemoryItem> items) {
        memoryStore.addMemories(items);
        notifyChanged();
    }

    public int removeMemoriesBySource(String source) {
        int removed = memoryStore.removeMemoriesBySource(source);
        if (removed > 0) {
            notifyChanged();
        }
        return removed;
    }

    /**
     * Scopes agent context retrieval to global memories plus memories of the given project.
     */
    public void setActiveProject(File projectDir) {
        memoryStore.setActiveProjectScope(projectDir != null
                ? com.github.axiomate.agentic.ide.config.ProjectStateManager.normalizePath(projectDir)
                : null);
        notifyChanged();
    }

    public String getActiveProjectPath() {
        return memoryStore.getActiveProjectScope();
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
        pruneEpisodes();
    }

    /** Task episodes are kept for recent history only; without a cap every finished task added one forever. */
    static final int MAX_TASK_EPISODES = 200;

    private void pruneEpisodes() {
        List<MemoryItem> episodes = memoryStore.getAllMemories().stream()
                .filter(m -> m.getType() == MemoryType.EPISODIC && m.getTags().contains("task-history"))
                .sorted(java.util.Comparator.comparing(m -> m.getTimestamp() == null ? "" : m.getTimestamp()))
                .toList();
        for (int i = 0; i < episodes.size() - MAX_TASK_EPISODES; i++) {
            memoryStore.removeMemory(episodes.get(i).getId());
        }
    }
}


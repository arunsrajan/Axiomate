package com.github.axiomate.agentic.ide.agent.memory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Interface defining the Agentic Memory store operations.
 */
public interface AgentMemoryStore {

    void addMemory(MemoryItem item);

    void removeMemory(String id);

    /**
     * Adds (or replaces by id) a batch of memories with a single persistence write.
     */
    default void addMemories(Collection<MemoryItem> items) {
        if (items == null) return;
        for (MemoryItem item : items) {
            addMemory(item);
        }
    }

    /**
     * Removes every memory whose {@link MemoryItem#getSource()} equals the given source key.
     *
     * @return number of memories removed
     */
    default int removeMemoriesBySource(String source) {
        if (source == null) return 0;
        int removed = 0;
        for (MemoryItem item : getAllMemories()) {
            if (source.equals(item.getSource())) {
                removeMemory(item.getId());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Restricts search and context retrieval to global memories plus memories of the given project.
     * A null scope disables filtering.
     */
    default void setActiveProjectScope(String projectPath) {
    }

    default String getActiveProjectScope() {
        return null;
    }

    List<MemoryItem> getAllMemories();

    List<MemoryItem> getMemoriesByType(MemoryType type);

    List<MemoryItem> search(String query, int limit);

    String getRelevantContext(String prompt);

    /**
     * Imports all memories from a JSON file, Markdown file, or an entire directory.
     *
     * @param sourceFileOrDir file or directory containing memory files
     * @return count of items imported
     * @throws IOException on I/O error
     */
    int importAllMemories(File sourceFileOrDir) throws IOException;

    /**
     * Exports all memories to a destination JSON file.
     *
     * @param targetFile destination file
     * @throws IOException on I/O error
     */
    void exportAllMemories(File targetFile) throws IOException;

    void clear();

    void save();
}


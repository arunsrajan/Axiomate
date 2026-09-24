package com.github.axiomate.agentic.ide.agent.memory;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Interface defining the Agentic Memory store operations.
 */
public interface AgentMemoryStore {

    void addMemory(MemoryItem item);

    void removeMemory(String id);

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


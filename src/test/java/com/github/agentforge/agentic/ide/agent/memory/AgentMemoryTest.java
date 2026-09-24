package com.github.agentforge.agentic.ide.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentMemoryTest {

    @Test
    @DisplayName("JsonAgentMemoryStore should add, search and retrieve memories")
    void testMemoryAddAndSearch(@TempDir Path tempDir) {
        Path memFile = tempDir.resolve("memories.json");
        JsonAgentMemoryStore store = new JsonAgentMemoryStore(memFile);

        MemoryItem item1 = new MemoryItem(MemoryType.PROJECT_RULE, "Calculator Division Rule",
                "Division by zero must throw ArithmeticException with tolerance checks", List.of("math", "exception"));
        store.addMemory(item1);

        MemoryItem item2 = new MemoryItem(MemoryType.LONG_TERM, "Architecture Note",
                "Decouple GUI panels from agent services using listeners", List.of("design", "gui"));
        store.addMemory(item2);

        List<MemoryItem> found = store.search("division", 5);
        assertFalse(found.isEmpty(), "Should find division memory");
        assertEquals("Calculator Division Rule", found.get(0).getTitle());

        String context = store.getRelevantContext("How do we divide numbers?");
        assertTrue(context.contains("Calculator Division Rule"), "Context should include relevant memory");
    }

    @Test
    @DisplayName("JsonAgentMemoryStore should import all memories from file")
    void testImportAllMemories(@TempDir Path tempDir) throws IOException {
        Path memFile = tempDir.resolve("store.json");
        JsonAgentMemoryStore store = new JsonAgentMemoryStore(memFile);

        // Create an import file
        String jsonToImport = """
                [
                  {
                    "id": "ext-1",
                    "type": "PROJECT_RULE",
                    "title": "Imported Rule",
                    "content": "All async operations must run off EDT",
                    "tags": ["async", "threading"],
                    "timestamp": "2026-09-24 10:00:00",
                    "importance": 0.9
                  },
                  {
                    "id": "ext-2",
                    "type": "LONG_TERM",
                    "title": "Imported Fact",
                    "content": "Maven shaded fat jar contains all runtime dependencies",
                    "tags": ["build", "maven"],
                    "timestamp": "2026-09-24 10:01:00",
                    "importance": 0.8
                  }
                ]
                """;

        Path importPath = tempDir.resolve("external_memories.json");
        Files.writeString(importPath, jsonToImport);

        int importedCount = store.importAllMemories(importPath.toFile());
        assertEquals(2, importedCount, "Should import exactly 2 memories");

        List<MemoryItem> asyncMem = store.search("async", 5);
        assertFalse(asyncMem.isEmpty());
        assertEquals("Imported Rule", asyncMem.get(0).getTitle());
    }

    @Test
    @DisplayName("MemoryManager should record episode")
    void testRecordEpisode() {
        MemoryManager manager = MemoryManager.getInstance();
        int initialSize = manager.getMemoryStore().getAllMemories().size();

        manager.recordEpisode("Refactor Calculator", "Successfully added power and squareRoot methods");

        int newSize = manager.getMemoryStore().getAllMemories().size();
        assertEquals(initialSize + 1, newSize, "Memory store should have 1 more episodic memory");

        List<MemoryItem> episodes = manager.getMemoryStore().getMemoriesByType(MemoryType.EPISODIC);
        assertFalse(episodes.isEmpty());
        assertTrue(episodes.stream().anyMatch(e -> e.getTitle().contains("Refactor Calculator")));
    }
}

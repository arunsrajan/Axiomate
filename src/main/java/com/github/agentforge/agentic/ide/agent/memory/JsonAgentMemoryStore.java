package com.github.agentforge.agentic.ide.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * File-backed implementation of AgentMemoryStore with JSON persistence,
 * search indexing, and batch import/export capabilities.
 */
public class JsonAgentMemoryStore implements AgentMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JsonAgentMemoryStore.class);

    private final ObjectMapper mapper;
    private final List<MemoryItem> memories = new CopyOnWriteArrayList<>();
    private final Path storagePath;

    public JsonAgentMemoryStore() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        Path dir = com.github.agentforge.agentic.ide.config.ConfigManager.getAppDirectory();
        this.storagePath = dir.resolve("agent_memory.json");
        loadMemories();
    }

    public JsonAgentMemoryStore(Path customPath) {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.storagePath = customPath;
        loadMemories();
    }

    private void loadMemories() {
        if (Files.exists(storagePath)) {
            try {
                List<MemoryItem> loaded = mapper.readValue(storagePath.toFile(), new TypeReference<List<MemoryItem>>() {});
                memories.clear();
                memories.addAll(loaded);
                log.info("Loaded {} memory items from {}", memories.size(), storagePath);
            } catch (Exception e) {
                log.error("Failed to load agent memories from {}", storagePath, e);
            }
        }

        if (memories.isEmpty()) {
            initDefaultMemories();
        }
    }

    private void initDefaultMemories() {
        addMemory(new MemoryItem(
                MemoryType.PROJECT_RULE,
                "Clean Code & Modern Java Standards",
                "Prefer Java 21+ idioms: records, pattern matching, switch expressions, clean exception handling, and SLF4J logging.",
                List.of("java", "standards", "clean-code")
        ));
        addMemory(new MemoryItem(
                MemoryType.LONG_TERM,
                "Testing Strategy",
                "Always generate unit tests with JUnit 5 and AssertJ assertions. Include edge cases such as division by zero and null bounds.",
                List.of("testing", "junit5", "quality")
        ));
        addMemory(new MemoryItem(
                MemoryType.PROJECT_RULE,
                "Architecture Guidelines",
                "Keep UI, Agent Services, and Tools strictly separated. Ensure asynchronous execution for long-running AI and process tasks.",
                List.of("architecture", "concurrency", "design")
        ));
        save();
    }

    @Override
    public synchronized void addMemory(MemoryItem item) {
        if (item == null) return;
        memories.removeIf(m -> m.getId().equals(item.getId()));
        memories.add(item);
        save();
    }

    @Override
    public synchronized void removeMemory(String id) {
        if (id == null) return;
        memories.removeIf(m -> m.getId().equals(id));
        save();
    }

    @Override
    public List<MemoryItem> getAllMemories() {
        return new ArrayList<>(memories);
    }

    @Override
    public List<MemoryItem> getMemoriesByType(MemoryType type) {
        return memories.stream()
                .filter(m -> m.getType() == type)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryItem> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return memories.stream().limit(limit).collect(Collectors.toList());
        }

        String[] queryTokens = query.toLowerCase().split("\\W+");

        record ScoredMemory(MemoryItem item, double score) {}

        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryItem m : memories) {
            double score = 0.0;
            String titleLower = m.getTitle() != null ? m.getTitle().toLowerCase() : "";
            String contentLower = m.getContent() != null ? m.getContent().toLowerCase() : "";

            for (String token : queryTokens) {
                if (token.isBlank()) continue;
                if (titleLower.contains(token)) score += 3.0;
                if (contentLower.contains(token)) score += 1.0;
                for (String tag : m.getTags()) {
                    if (tag.equalsIgnoreCase(token)) score += 2.0;
                }
            }

            score *= (0.5 + m.getImportance());

            if (score > 0) {
                scored.add(new ScoredMemory(m, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        return scored.stream()
                .limit(limit)
                .map(ScoredMemory::item)
                .collect(Collectors.toList());
    }

    @Override
    public String getRelevantContext(String prompt) {
        List<MemoryItem> relevant = search(prompt, 4);
        if (relevant.isEmpty()) {
            // fallback to project rules
            relevant = getMemoriesByType(MemoryType.PROJECT_RULE);
        }

        if (relevant.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("### Agentic AI Memory & Knowledge Context:\n");
        for (MemoryItem item : relevant) {
            sb.append("- **[").append(item.getType()).append("] ").append(item.getTitle()).append("**: ")
                    .append(item.getContent().replace("\n", " "))
                    .append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    @Override
    public int importAllMemories(File sourceFileOrDir) throws IOException {
        if (sourceFileOrDir == null || !sourceFileOrDir.exists()) {
            throw new IllegalArgumentException("Source file or directory does not exist: " + sourceFileOrDir);
        }

        int count = 0;
        if (sourceFileOrDir.isDirectory()) {
            File[] files = sourceFileOrDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    count += importAllMemories(file);
                }
            }
        } else {
            String name = sourceFileOrDir.getName().toLowerCase();
            if (name.endsWith(".json")) {
                try {
                    List<MemoryItem> imported = mapper.readValue(sourceFileOrDir, new TypeReference<List<MemoryItem>>() {});
                    for (MemoryItem item : imported) {
                        addMemory(item);
                        count++;
                    }
                } catch (Exception e) {
                    // Try parsing single object
                    try {
                        MemoryItem single = mapper.readValue(sourceFileOrDir, MemoryItem.class);
                        addMemory(single);
                        count++;
                    } catch (Exception ex) {
                        log.warn("Failed to parse JSON memory file {}", sourceFileOrDir.getAbsolutePath(), ex);
                    }
                }
            } else if (name.endsWith(".md") || name.endsWith(".txt")) {
                // Parse markdown/text file
                String content = Files.readString(sourceFileOrDir.toPath());
                String title = sourceFileOrDir.getName();
                MemoryType type = name.contains("rule") ? MemoryType.PROJECT_RULE : MemoryType.LONG_TERM;
                MemoryItem item = new MemoryItem(type, title, content, List.of("imported", name));
                addMemory(item);
                count++;
            }
        }

        save();
        log.info("Imported {} memory items from {}", count, sourceFileOrDir.getAbsolutePath());
        return count;
    }

    @Override
    public void exportAllMemories(File targetFile) throws IOException {
        if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }
        mapper.writeValue(targetFile, memories);
        log.info("Exported {} memories to {}", memories.size(), targetFile.getAbsolutePath());
    }

    @Override
    public synchronized void clear() {
        memories.clear();
        save();
    }

    @Override
    public synchronized void save() {
        try {
            if (storagePath.getParent() != null && !Files.exists(storagePath.getParent())) {
                Files.createDirectories(storagePath.getParent());
            }
            mapper.writeValue(storagePath.toFile(), memories);
            log.debug("Saved {} memories to {}", memories.size(), storagePath);
        } catch (IOException e) {
            log.error("Failed to save memories to {}", storagePath, e);
        }
    }
}

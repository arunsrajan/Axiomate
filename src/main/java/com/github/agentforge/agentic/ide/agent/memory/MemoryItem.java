package com.github.agentforge.agentic.ide.agent.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single memory unit in the agentic memory system.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryItem {

    private String id;
    private MemoryType type;
    private String title;
    private String content;
    private List<String> tags = new ArrayList<>();
    private String timestamp;
    private double importance = 0.5; // 0.0 to 1.0

    public MemoryItem() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public MemoryItem(MemoryType type, String title, String content) {
        this();
        this.type = type;
        this.title = title;
        this.content = content;
    }

    public MemoryItem(MemoryType type, String title, String content, List<String> tags) {
        this(type, title, content);
        if (tags != null) {
            this.tags = new ArrayList<>(tags);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MemoryType getType() {
        return type;
    }

    public void setType(MemoryType type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = Math.max(0.0, Math.min(1.0, importance));
    }

    @Override
    public String toString() {
        return "[" + type + "] " + title + " (" + timestamp + ")";
    }
}

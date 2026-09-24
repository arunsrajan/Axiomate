package com.github.agentforge.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Definition and metadata for an AI model supported by a provider.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelDefinition {

    private String id;
    private String displayName;
    private int maxContextTokens = 128_000;
    private int maxOutputTokens = 4_096;
    private List<String> tags = new ArrayList<>();

    public ModelDefinition() {
    }

    public ModelDefinition(String id, String displayName, int maxContextTokens, int maxOutputTokens, List<String> tags) {
        this.id = id;
        this.displayName = displayName;
        this.maxContextTokens = maxContextTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName != null ? displayName : id;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    @Override
    public String toString() {
        return getDisplayName() + " (" + (maxContextTokens / 1000) + "k ctx)";
    }
}

package com.github.agentforge.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an AI model provider (Anthropic, OpenAI, Gemini, Local/Custom, Mock).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderConfig {

    private String id;
    private String name;
    private String baseUrl;
    private String apiKey = "";
    private boolean enabled = true;
    private List<ModelDefinition> models = new ArrayList<>();
    private String defaultModel = "";

    public ProviderConfig() {
    }

    public ProviderConfig(String id, String name, String baseUrl, String defaultModel, List<ModelDefinition> models) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.models = models != null ? new ArrayList<>(models) : new ArrayList<>();
        this.enabled = true;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ModelDefinition> getModels() {
        return models;
    }

    public void setModels(List<ModelDefinition> models) {
        this.models = models != null ? new ArrayList<>(models) : new ArrayList<>();
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public ModelDefinition findModel(String modelId) {
        for (ModelDefinition m : models) {
            if (m.getId().equalsIgnoreCase(modelId)) {
                return m;
            }
        }
        return models.isEmpty() ? null : models.get(0);
    }

    @Override
    public String toString() {
        return name + (enabled ? "" : " (Disabled)");
    }
}

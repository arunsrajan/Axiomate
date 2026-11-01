package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an AI model provider.
 * Supports multiple instances of ANTHROPIC, GEMINI, OPENAI, CUSTOM, or MOCK providers,
 * each with their own endpoint URL, API key, model list, and settings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderConfig {

    private String id;
    private String providerType = "OPENAI"; // ANTHROPIC, OPENAI, GEMINI, CUSTOM, MOCK
    private String name;
    private String baseUrl;
    private String apiKey = "";
    private boolean enabled = true;
    private List<ModelDefinition> models = new ArrayList<>();
    private String defaultModel = "";
    /** Context handling (off unless ticked in Settings; the Colibri preset has them on). */
    private boolean compactToolResults = false;
    private boolean capToolOutputs = false;
    private boolean stablePromptPrefix = false;

    /** Replace older tool outputs with short notes once a request passes 75% of the model's context. */
    public boolean isCompactToolResults() {
        return compactToolResults;
    }

    public void setCompactToolResults(boolean compactToolResults) {
        this.compactToolResults = compactToolResults;
    }

    /** Cut a single tool output the model receives to a size that fits its context. */
    public boolean isCapToolOutputs() {
        return capToolOutputs;
    }

    public void setCapToolOutputs(boolean capToolOutputs) {
        this.capToolOutputs = capToolOutputs;
    }

    /** Keep the system prompt the same from turn to turn (per-turn context goes into the request) for prefix reuse. */
    public boolean isStablePromptPrefix() {
        return stablePromptPrefix;
    }

    public void setStablePromptPrefix(boolean stablePromptPrefix) {
        this.stablePromptPrefix = stablePromptPrefix;
    }

    public ProviderConfig() {
    }

    public ProviderConfig(String id, String name, String baseUrl, String defaultModel, List<ModelDefinition> models) {
        this(id, inferProviderType(id), name, baseUrl, defaultModel, models);
    }

    public ProviderConfig(String id, String providerType, String name, String baseUrl, String defaultModel, List<ModelDefinition> models) {
        this.id = id;
        this.providerType = (providerType != null && !providerType.isBlank()) ? providerType.toUpperCase() : inferProviderType(id);
        this.name = name;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.models = models != null ? new ArrayList<>(models) : new ArrayList<>();
        this.enabled = true;
    }

    public static String inferProviderType(String id) {
        if (id == null) return "CUSTOM";
        String upper = id.toUpperCase();
        if (upper.contains("ANTHROPIC") || upper.contains("CLAUDE")) return "ANTHROPIC";
        if (upper.contains("GEMINI")) return "GEMINI";
        if (upper.contains("OPENAI") || upper.contains("GPT")) return "OPENAI";
        if (upper.contains("MOCK")) return "MOCK";
        return "CUSTOM";
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isAnthropicType() {
        String type = getProviderType();
        return "ANTHROPIC".equalsIgnoreCase(type) || "CUSTOM_ANTHROPIC".equalsIgnoreCase(type)
                || type.contains("ANTHROPIC") || type.contains("CLAUDE");
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderType() {
        if (providerType == null || providerType.isBlank()) {
            providerType = inferProviderType(id);
        }
        return providerType.toUpperCase();
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType != null ? providerType.toUpperCase() : "CUSTOM";
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

    /**
     * The model with exactly this id, or null. Use this for a model's own limits and capabilities:
     * {@link #findModel} falls back to the first model, whose settings don't apply to another model.
     */
    public ModelDefinition findExactModel(String modelId) {
        if (modelId == null) return null;
        for (ModelDefinition m : models) {
            if (m.getId().equalsIgnoreCase(modelId)) {
                return m;
            }
        }
        return null;
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
        return name + " (" + getProviderType() + ")" + (enabled ? "" : " [Disabled]");
    }
}


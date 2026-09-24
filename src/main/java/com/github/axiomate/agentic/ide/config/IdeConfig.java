package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.*;

/**
 * Configuration model for the Axiomate AI Agent IDE, supporting multi-provider setups,
 * multiple models, task-based routing, and context compression thresholds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdeConfig {

    private String theme = "FlatLaf Darcula";
    private int fontSize = 14;
    private String fontFamily = "Consolas";

    // Active Selection
    private String activeProviderId = "MOCK";
    private String activeModelId = "mock-agent";

    // Auto-Routing & Compression
    private boolean autoRoutingEnabled = true;
    private double autoCompressionThreshold = 0.95; // 95% limit triggers compression

    // File Mentions (@)
    private boolean fileMentionsEnabled = true;
    private String mentionTriggerChar = "@";

    private double temperature = 0.2;
    private String systemPrompt = """
            You are an autonomous AI software engineer inside the Axiomate IDE.
            You have access to persistent agentic memory, multi-provider model routing, and tools.
            Always provide concise, correct, production-grade code with explanations.
            """;
    private boolean autoSave = false;
    private String lastWorkspacePath = "";

    // Multi-Provider Registry
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    // Task-Based Model Routing (TaskType -> "PROVIDER_ID:MODEL_ID")
    private Map<TaskType, String> taskRouting = new EnumMap<>(TaskType.class);

    public IdeConfig() {
        initDefaultProviders();
        initDefaultTaskRouting();
    }

    private void initDefaultProviders() {
        // 1. Anthropic Provider
        ProviderConfig anthropic = new ProviderConfig(
                "ANTHROPIC", "Anthropic Claude",
                "https://api.anthropic.com/v1",
                "claude-3-7-sonnet",
                List.of(
                        new ModelDefinition("claude-3-7-sonnet", "Claude 3.7 Sonnet (Hybrid Reasoning)", 200_000, 8_192, List.of("reasoning", "coding", "refactor")),
                        new ModelDefinition("claude-3-5-sonnet", "Claude 3.5 Sonnet (Coding Champion)", 200_000, 8_192, List.of("coding", "tools", "refactor")),
                        new ModelDefinition("claude-3-5-haiku", "Claude 3.5 Haiku (Lightning Fast)", 200_000, 4_096, List.of("fast", "explain"))
                )
        );
        providers.put("ANTHROPIC", anthropic);
 
        // 2. Custom Anthropic Provider (Claude-Compatible Endpoint / Proxy / Bedrock / LiteLLM)
        ProviderConfig customAnthropic = new ProviderConfig(
                "CUSTOM_ANTHROPIC", "ANTHROPIC", "Custom Anthropic (Claude API)",
                "https://api.anthropic.com/v1",
                "claude-3-7-sonnet",
                List.of(
                        new ModelDefinition("claude-3-7-sonnet", "Claude 3.7 Sonnet (Hybrid Reasoning)", 200_000, 8_192, List.of("reasoning", "coding", "refactor")),
                        new ModelDefinition("claude-3-5-sonnet", "Claude 3.5 Sonnet (Coding Champion)", 200_000, 8_192, List.of("coding", "tools", "refactor")),
                        new ModelDefinition("claude-3-5-haiku", "Claude 3.5 Haiku (Lightning Fast)", 200_000, 4_096, List.of("fast", "explain"))
                )
        );
        providers.put("CUSTOM_ANTHROPIC", customAnthropic);

        // 2. OpenAI Provider
        ProviderConfig openai = new ProviderConfig(
                "OPENAI", "OpenAI",
                "https://api.openai.com/v1",
                "gpt-4o",
                List.of(
                        new ModelDefinition("gpt-4o", "GPT-4o (Multimodal & Fast)", 128_000, 4_096, List.of("coding", "tools", "general")),
                        new ModelDefinition("gpt-4o-mini", "GPT-4o Mini (Cost-Effective)", 128_000, 4_096, List.of("fast", "explain")),
                        new ModelDefinition("o1", "o1 (Deep Reasoning)", 200_000, 32_768, List.of("reasoning", "refactor")),
                        new ModelDefinition("o3-mini", "o3-mini (High-Speed Reasoning)", 200_000, 16_384, List.of("reasoning", "coding"))
                )
        );
        providers.put("OPENAI", openai);

        // 3. Google Gemini Provider
        ProviderConfig gemini = new ProviderConfig(
                "GEMINI", "Google Gemini",
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.0-flash",
                List.of(
                        new ModelDefinition("gemini-2.0-flash", "Gemini 2.0 Flash (Ultra Fast & Native Audio)", 1_000_000, 8_192, List.of("fast", "tools", "explain")),
                        new ModelDefinition("gemini-1.5-pro", "Gemini 1.5 Pro (1M Token Massive Context)", 2_000_000, 8_192, List.of("massive-context", "refactor", "tests")),
                        new ModelDefinition("gemini-1.5-flash", "Gemini 1.5 Flash (Lightweight & Efficient)", 1_000_000, 8_192, List.of("fast", "explain"))
                )
        );
        providers.put("GEMINI", gemini);

        // 4. Custom / Local Provider (Ollama, LM Studio, vLLM, DeepSeek)
        ProviderConfig custom = new ProviderConfig(
                "CUSTOM", "Custom / Local (Ollama)",
                "http://localhost:11434/v1",
                "qwen2.5-coder",
                List.of(
                        new ModelDefinition("qwen2.5-coder", "Qwen 2.5 Coder 32B (Local)", 32_768, 4_096, List.of("coding", "local")),
                        new ModelDefinition("llama3.2", "Llama 3.2 8B (Local)", 8_192, 2_048, List.of("fast", "local")),
                        new ModelDefinition("deepseek-coder", "DeepSeek Coder (Local/API)", 64_000, 4_096, List.of("coding", "refactor"))
                )
        );
        providers.put("CUSTOM", custom);

        // 5. Mock Simulator Provider
        ProviderConfig mock = new ProviderConfig(
                "MOCK", "Offline Simulator (Mock)",
                "local://simulator",
                "mock-agent",
                List.of(
                        new ModelDefinition("mock-agent", "Offline Intelligent Agent Simulator", 128_000, 4_096, List.of("offline", "simulation", "all"))
                )
        );
        providers.put("MOCK", mock);
    }

    private void initDefaultTaskRouting() {
        taskRouting.put(TaskType.GENERAL, "OPENAI:gpt-4o");
        taskRouting.put(TaskType.EXPLAIN, "GEMINI:gemini-2.0-flash");
        taskRouting.put(TaskType.REFACTOR, "ANTHROPIC:claude-3-7-sonnet");
        taskRouting.put(TaskType.GENERATE_TESTS, "OPENAI:gpt-4o");
        taskRouting.put(TaskType.DEBUG_FIX, "ANTHROPIC:claude-3-7-sonnet");
        taskRouting.put(TaskType.TERMINAL_TOOL, "GEMINI:gemini-2.0-flash");
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = Math.max(10, Math.min(32, fontSize));
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public String getActiveProviderId() {
        return activeProviderId != null ? activeProviderId : "MOCK";
    }

    public void setActiveProviderId(String activeProviderId) {
        this.activeProviderId = activeProviderId;
    }

    public String getActiveModelId() {
        return activeModelId != null ? activeModelId : "mock-agent";
    }

    public void setActiveModelId(String activeModelId) {
        this.activeModelId = activeModelId;
    }

    public boolean isAutoRoutingEnabled() {
        return autoRoutingEnabled;
    }

    public void setAutoRoutingEnabled(boolean autoRoutingEnabled) {
        this.autoRoutingEnabled = autoRoutingEnabled;
    }

    public double getAutoCompressionThreshold() {
        return autoCompressionThreshold;
    }

    public void setAutoCompressionThreshold(double autoCompressionThreshold) {
        this.autoCompressionThreshold = Math.max(0.5, Math.min(0.99, autoCompressionThreshold));
    }

    public boolean isFileMentionsEnabled() {
        return fileMentionsEnabled;
    }

    public void setFileMentionsEnabled(boolean fileMentionsEnabled) {
        this.fileMentionsEnabled = fileMentionsEnabled;
    }

    public String getMentionTriggerChar() {
        return mentionTriggerChar != null ? mentionTriggerChar : "@";
    }

    public void setMentionTriggerChar(String mentionTriggerChar) {
        this.mentionTriggerChar = mentionTriggerChar != null ? mentionTriggerChar : "@";
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public boolean isAutoSave() {
        return autoSave;
    }

    public void setAutoSave(boolean autoSave) {
        this.autoSave = autoSave;
    }

    public String getLastWorkspacePath() {
        return lastWorkspacePath;
    }

    public void setLastWorkspacePath(String lastWorkspacePath) {
        this.lastWorkspacePath = lastWorkspacePath;
    }

    public Map<String, ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderConfig> providers) {
        this.providers = providers != null ? providers : new LinkedHashMap<>();
    }

    public ProviderConfig getProvider(String providerId) {
        return providers.get(providerId);
    }

    public void addProvider(ProviderConfig provider) {
        if (provider != null && provider.getId() != null) {
            this.providers.put(provider.getId(), provider);
        }
    }

    public void removeProvider(String providerId) {
        if (providerId != null) {
            this.providers.remove(providerId);
        }
    }

    public List<ProviderConfig> getProvidersByApiType(String apiType) {
        if (apiType == null || apiType.isBlank()) {
            return new ArrayList<>(providers.values());
        }
        return providers.values().stream()
                .filter(p -> apiType.equalsIgnoreCase(p.getProviderType())
                        || (apiType.equalsIgnoreCase("ANTHROPIC") && p.isAnthropicType()))
                .toList();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<ProviderConfig> getAnthropicProviders() {
        return getProvidersByApiType("ANTHROPIC");
    }

    public Map<TaskType, String> getTaskRouting() {
        return taskRouting;
    }

    public void setTaskRouting(Map<TaskType, String> taskRouting) {
        this.taskRouting = taskRouting != null ? taskRouting : new EnumMap<>(TaskType.class);
    }

    // Backward-compatibility getters/setters (ignored by Jackson to avoid polluting or overwriting config)
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getAiProvider() {
        return getActiveProviderId();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setAiProvider(String aiProvider) {
        setActiveProviderId(aiProvider);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getModelName() {
        return getActiveModelId();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setModelName(String modelName) {
        setActiveModelId(modelName);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getApiKey() {
        ProviderConfig prov = getProvider(getActiveProviderId());
        return prov != null ? prov.getApiKey() : "";
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setApiKey(String apiKey) {
        ProviderConfig prov = getProvider(getActiveProviderId());
        if (prov != null) {
            prov.setApiKey(apiKey);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getApiBaseUrl() {
        ProviderConfig prov = getProvider(getActiveProviderId());
        return prov != null ? prov.getBaseUrl() : "";
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setApiBaseUrl(String apiBaseUrl) {
        ProviderConfig prov = getProvider(getActiveProviderId());
        if (prov != null) {
            prov.setBaseUrl(apiBaseUrl);
        }
    }
}


package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.config.ProviderConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Factory for creating ChatLanguageModel instances dynamically across
 * multiple Anthropic, OpenAI, Google Gemini, and Local/Custom provider configurations.
 */
public class UniversalChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(UniversalChatModelFactory.class);

    public static ChatLanguageModel createChatModel(ProviderConfig config, String modelName, double temperature) {
        String providerId = config != null ? config.getId() : "OPENAI";
        String providerType = config != null ? config.getProviderType() : "OPENAI";
        String apiKey = config != null && config.getApiKey() != null && !config.getApiKey().isBlank()
                ? config.getApiKey().trim() : "demo";
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.openai.com/v1";
        String targetModel = (modelName != null && !modelName.isBlank())
                ? modelName : (config != null ? config.getDefaultModel() : "gpt-4o");

        log.info("Instantiating ChatLanguageModel for Provider [{}] (Type: {}) with Model [{}] at BaseURL [{}]",
                providerId, providerType, targetModel, baseUrl);

        String normalizedType = providerType != null ? providerType.trim().toUpperCase() : "OPENAI";
        if (normalizedType.contains("ANTHROPIC") || normalizedType.contains("CLAUDE")) {
            normalizedType = "ANTHROPIC";
        } else if (normalizedType.contains("GEMINI")) {
            normalizedType = "GEMINI";
        }

        return switch (normalizedType) {
            case "ANTHROPIC" -> {
                AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(targetModel)
                        .temperature(temperature)
                        .timeout(Duration.ofSeconds(60));
                if (baseUrl != null && !baseUrl.isBlank()) {
                    String formattedUrl = baseUrl.trim();
                    if (!formattedUrl.endsWith("/")) {
                        formattedUrl = formattedUrl + "/";
                    }
                    builder.baseUrl(formattedUrl);
                }
                yield builder.build();
            }
            case "GEMINI" -> {
                GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(targetModel)
                        .temperature(temperature)
                        .timeout(Duration.ofSeconds(60));
                yield builder.build();
            }
            default -> { // OPENAI or CUSTOM (Ollama, LM Studio, vLLM, DeepSeek, etc.)
                yield OpenAiChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(targetModel)
                        .temperature(temperature)
                        .timeout(Duration.ofSeconds(60))
                        .build();
            }
        };
    }
}


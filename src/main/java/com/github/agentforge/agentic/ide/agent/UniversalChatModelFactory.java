package com.github.agentforge.agentic.ide.agent;

import com.github.agentforge.agentic.ide.config.ProviderConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Factory for creating ChatLanguageModel instances dynamically across
 * Anthropic, OpenAI, Google Gemini, and Local/Custom endpoints.
 */
public class UniversalChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(UniversalChatModelFactory.class);

    public static ChatLanguageModel createChatModel(ProviderConfig config, String modelName, double temperature) {
        String providerId = config != null ? config.getId().toUpperCase() : "OPENAI";
        String apiKey = config != null && config.getApiKey() != null && !config.getApiKey().isBlank()
                ? config.getApiKey().trim() : "demo";
        String baseUrl = config != null ? config.getBaseUrl() : "https://api.openai.com/v1";
        String targetModel = (modelName != null && !modelName.isBlank())
                ? modelName : (config != null ? config.getDefaultModel() : "gpt-4o");

        log.info("Instantiating ChatLanguageModel for Provider [{}] with Model [{}] at BaseURL [{}]",
                providerId, targetModel, baseUrl);

        return switch (providerId) {
            case "ANTHROPIC" -> {
                AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(targetModel)
                        .temperature(temperature)
                        .timeout(Duration.ofSeconds(60));
                if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.contains("anthropic.com")) {
                    builder.baseUrl(baseUrl);
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
            default -> { // OPENAI or CUSTOM (Ollama, LM Studio, etc.)
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

package com.github.axiomate.agentic.ide.features.planning;

import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feature 4: Effort and cost estimator.
 * It predicts time, token spend, and confidence for a task before running it.
 */
public class EffortCostEstimator {

    private static final Logger log = LoggerFactory.getLogger(EffortCostEstimator.class);
    private static EffortCostEstimator instance;

    private EffortCostEstimator() {}

    public static synchronized EffortCostEstimator getInstance() {
        if (instance == null) {
            instance = new EffortCostEstimator();
        }
        return instance;
    }

    public EffortEstimate estimate(String prompt, String contextCode, String providerId) {
        int promptTokens = TokenTracker.estimateTokens(prompt != null ? prompt : "");
        int contextTokens = TokenTracker.estimateTokens(contextCode != null ? contextCode : "");
        int totalInputTokens = promptTokens + contextTokens + 400; // system prompt + tools overhead

        String lower = prompt != null ? prompt.toLowerCase() : "";
        int estimatedOutputTokens;
        int durationSeconds;
        double confidence;
        String complexity;

        if (lower.contains("refactor") || lower.contains("redesign") || lower.contains("rewrite")) {
            estimatedOutputTokens = Math.max(800, contextTokens + 300);
            durationSeconds = 18 + (totalInputTokens / 500);
            confidence = 0.88;
            complexity = "HIGH";
        } else if (lower.contains("test") || lower.contains("junit")) {
            estimatedOutputTokens = Math.max(600, contextTokens / 2 + 250);
            durationSeconds = 12 + (totalInputTokens / 700);
            confidence = 0.94;
            complexity = "MEDIUM";
        } else if (lower.contains("explain") || lower.contains("what does")) {
            estimatedOutputTokens = 500;
            durationSeconds = 6 + (totalInputTokens / 1000);
            confidence = 0.98;
            complexity = "LOW";
        } else if (lower.contains("bug") || lower.contains("fix") || lower.contains("error")) {
            estimatedOutputTokens = 600;
            durationSeconds = 14 + (totalInputTokens / 600);
            confidence = 0.89;
            complexity = "MEDIUM";
        } else {
            estimatedOutputTokens = 750;
            durationSeconds = 10 + (totalInputTokens / 800);
            confidence = 0.91;
            complexity = "MEDIUM";
        }

        int totalTokens = totalInputTokens + estimatedOutputTokens;

        // Pricing per million tokens (standard benchmark rates)
        // Claude 3.7 Sonnet: $3 / M input, $15 / M output
        // GPT-4o: $2.50 / M input, $10 / M output
        // Gemini 2.0 Flash: $0.10 / M input, $0.40 / M output
        // Local: $0.00
        double costAnthropic = (totalInputTokens * 0.000003) + (estimatedOutputTokens * 0.000015);
        double costOpenAI = (totalInputTokens * 0.0000025) + (estimatedOutputTokens * 0.000010);
        double costGemini = (totalInputTokens * 0.0000001) + (estimatedOutputTokens * 0.0000004);
        double costLocal = 0.0;

        Map<String, Double> costs = new LinkedHashMap<>();
        costs.put("ANTHROPIC", costAnthropic);
        costs.put("OPENAI", costOpenAI);
        costs.put("GEMINI", costGemini);
        costs.put("CUSTOM", costLocal);
        costs.put("MOCK", costLocal);

        double chosenCost = costs.getOrDefault(providerId != null ? providerId.toUpperCase() : "OPENAI", costOpenAI);

        return new EffortEstimate(
                durationSeconds,
                totalInputTokens,
                estimatedOutputTokens,
                totalTokens,
                chosenCost,
                costs,
                confidence,
                complexity
        );
    }
}

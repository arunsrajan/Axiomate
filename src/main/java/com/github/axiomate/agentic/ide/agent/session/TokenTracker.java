package com.github.axiomate.agentic.ide.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Tracks token usage, model context limits, and usage percentages.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenTracker {

    private long promptTokens = 0;
    private long completionTokens = 0;
    private long totalTokens = 0;
    private int maxContextTokens = 128_000;

    public TokenTracker() {
    }

    public TokenTracker(int maxContextTokens) {
        this.maxContextTokens = Math.max(1_000, maxContextTokens);
    }

    public synchronized void recordUsage(long promptTokens, long completionTokens) {
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    public synchronized void setEstimatedUsage(long currentContextTokens) {
        this.promptTokens = currentContextTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
    }

    public double getUsagePercentage() {
        if (maxContextTokens <= 0) return 0.0;
        return ((double) totalTokens / maxContextTokens) * 100.0;
    }

    public boolean isThresholdReached(double thresholdRatio) {
        if (maxContextTokens <= 0) return false;
        return ((double) totalTokens / maxContextTokens) >= thresholdRatio;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Standard heuristic: ~4 characters per token
        return Math.max(1, text.length() / 4);
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(long promptTokens) {
        this.promptTokens = promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(long completionTokens) {
        this.completionTokens = completionTokens;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = Math.max(1_000, maxContextTokens);
    }

    public void reset() {
        this.promptTokens = 0;
        this.completionTokens = 0;
        this.totalTokens = 0;
    }

    public String getFormattedDisplay() {
        return String.format("Tokens: %,d / %,d (%.1f%%)", totalTokens, maxContextTokens, getUsagePercentage());
    }
}


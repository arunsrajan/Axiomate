package com.github.axiomate.agentic.ide.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Tracks token usage against the model's context window.
 * <p>
 * Two different numbers are kept: the tokens used so far across all requests ({@link #getTotalTokens()}, what a
 * provider bills) and the size of the conversation the next request will send ({@link #getContextTokens()}), which
 * drives the context meter and the automatic compression threshold. Every agent step resends the whole conversation,
 * so the first grows with each step while the second only grows with the conversation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenTracker {

    private long promptTokens = 0;
    private long completionTokens = 0;
    private long totalTokens = 0;
    /** Current conversation size. Absent in sessions saved by older versions, which start at 0. */
    private long contextTokens = 0;
    private int maxContextTokens = 128_000;

    public TokenTracker() {
    }

    public TokenTracker(int maxContextTokens) {
        this.maxContextTokens = Math.max(1_000, maxContextTokens);
    }

    /** Adds estimated tokens for content added to the conversation (e.g. a new message). */
    public synchronized void recordUsage(long promptTokens, long completionTokens) {
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
        this.contextTokens += promptTokens + completionTokens;
    }

    /**
     * Records a request's usage as reported by the provider. Its input tokens are the whole conversation that was
     * sent, so the context size becomes input + output rather than growing by them.
     */
    public synchronized void recordProviderUsage(long inputTokens, long outputTokens) {
        this.promptTokens += inputTokens;
        this.completionTokens += outputTokens;
        this.totalTokens = this.promptTokens + this.completionTokens;
        this.contextTokens = inputTokens + outputTokens;
    }

    /** Sets the context size, e.g. after compression shrank the conversation. */
    public synchronized void setEstimatedUsage(long currentContextTokens) {
        this.contextTokens = Math.max(0, currentContextTokens);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public double getUsagePercentage() {
        if (maxContextTokens <= 0) return 0.0;
        return ((double) contextTokens / maxContextTokens) * 100.0;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isThresholdReached(double thresholdRatio) {
        if (maxContextTokens <= 0) return false;
        return ((double) contextTokens / maxContextTokens) >= thresholdRatio;
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

    public long getContextTokens() {
        return contextTokens;
    }

    public void setContextTokens(long contextTokens) {
        this.contextTokens = contextTokens;
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
        this.contextTokens = 0;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getFormattedDisplay() {
        return String.format("Tokens: %,d / %,d (%.1f%%)", contextTokens, maxContextTokens, getUsagePercentage());
    }
}


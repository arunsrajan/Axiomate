package com.github.axiomate.agentic.ide.agent.session;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for compressing conversation history when token consumption reaches 95% of maximum context.
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    public record CompressionResult(boolean compressed, int tokensSaved, String summary) {}

    /**
     * Checks if the session's token usage has exceeded the threshold ratio (e.g. 0.95 = 95%)
     * and performs semantic compression if necessary.
     */
    public static CompressionResult compressIfExceeded(AgentSession session, double thresholdRatio) {
        if (session == null || !session.getTokenTracker().isThresholdReached(thresholdRatio)) {
            return new CompressionResult(false, 0, "Token usage within safe limits.");
        }

        List<AgentMessage> messages = session.getMessages();
        if (messages.size() <= 4) {
            return new CompressionResult(false, 0, "Message history too brief to compress.");
        }

        long tokensBefore = session.getTokenTracker().getTotalTokens();
        log.warn("Token usage reached {}% on session '{}'. Triggering 95% context compression...",
                String.format("%.1f", session.getTokenTracker().getUsagePercentage()), session.getName());

        // Extract messages to compress (all except the last 3 messages)
        int keepCount = Math.min(3, messages.size());
        int compressUntil = messages.size() - keepCount;

        List<AgentMessage> toCompress = new ArrayList<>(messages.subList(0, compressUntil));
        List<AgentMessage> toRetain = new ArrayList<>(messages.subList(compressUntil, messages.size()));

        // Synthesize condensed summary of early turns
        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append("### Condensed Context Summary (Compressed at 95% token limit):\n");
        for (AgentMessage msg : toCompress) {
            String roleName = msg.getRole().name();
            String snippet = msg.getContent().length() > 120
                    ? msg.getContent().substring(0, 117) + "..."
                    : msg.getContent();
            summaryBuilder.append("- **").append(roleName).append("**: ").append(snippet.replace("\n", " ")).append("\n");
        }

        // Save compressed context into episodic long-term memory
        MemoryManager.getInstance().addMemory(new MemoryItem(
                MemoryType.EPISODIC,
                "Compressed Session Memory: " + session.getName(),
                summaryBuilder.toString(),
                List.of("compressed-context", session.getId())
        ));

        // Reassemble session messages
        List<AgentMessage> newHistory = new ArrayList<>();
        newHistory.add(new AgentMessage(AgentRole.SYSTEM, summaryBuilder.toString()));
        newHistory.addAll(toRetain);

        session.setMessages(newHistory);

        // Recalculate estimated tokens
        long newTokens = 0;
        for (AgentMessage m : newHistory) {
            newTokens += TokenTracker.estimateTokens(m.getContent());
        }
        session.getTokenTracker().setEstimatedUsage(newTokens);

        int tokensSaved = (int) Math.max(0, tokensBefore - newTokens);
        String summary = String.format("Compressed %d earlier messages into summary. Saved %,d tokens (Usage down to %.1f%%).",
                toCompress.size(), tokensSaved, session.getTokenTracker().getUsagePercentage());

        log.info(summary);
        return new CompressionResult(true, tokensSaved, summary);
    }
}


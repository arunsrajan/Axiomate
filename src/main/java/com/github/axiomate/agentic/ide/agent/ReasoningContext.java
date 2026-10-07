package com.github.axiomate.agentic.ide.agent;

import dev.langchain4j.data.message.AiMessage;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Provider-neutral store for model reasoning ("thinking", DeepSeek's reasoning_content).
 * <ul>
 *   <li>{@link #LAST_REASONING}: the reasoning of the most recent model call, read by the agent loop to show it.</li>
 *   <li>Replay map: reasoning of assistant turns that called tools, keyed by the AiMessage instance, so the
 *   provider client can send it back with that turn on the next request (required by DeepSeek thinking mode
 *   and Claude extended thinking; without it the model loses its plan and repeats tool calls).</li>
 * </ul>
 */
public final class ReasoningContext {

    public static final ThreadLocal<String> LAST_REASONING = new ThreadLocal<>();

    private static final Map<AiMessage, String> REPLAY = Collections.synchronizedMap(new IdentityHashMap<>());

    private ReasoningContext() {
    }

    public static void rememberForReplay(AiMessage message, String reasoning) {
        if (message != null && reasoning != null && !reasoning.isBlank()) {
            REPLAY.put(message, reasoning);
        }
    }

    public static String replayFor(AiMessage message) {
        return message == null ? null : REPLAY.get(message);
    }

    /** Reads and clears the reasoning of the last model call. */
    public static String takeLast() {
        try {
            String t = LAST_REASONING.get();
            return t != null && !t.isBlank() ? t : null;
        } finally {
            LAST_REASONING.remove();
        }
    }

    /** Called when an agent task ends so replay entries don't accumulate. */
    public static void clear() {
        REPLAY.clear();
        LAST_REASONING.remove();
        dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper.clearThinkingReplay();
    }
}

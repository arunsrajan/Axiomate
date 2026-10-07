package com.github.axiomate.agentic.ide.agent;

/**
 * Callback listener interface for streaming agent responses, thinking steps, and tool execution.
 */
public interface AgentListener {

    /** Answer text as it is generated (streaming), or the whole answer at once for non-streaming models. */
    void onToken(String token);

    /** Model reasoning as it is generated. Streamed reasoning is not repeated through {@link #onThinking}. */
    default void onReasoningToken(String token) {
    }

    void onThinking(String thought);

    void onToolCall(String toolName, String input);

    void onToolResult(String toolName, String output);

    void onComplete(String fullResponse);

    void onError(Throwable throwable);
}


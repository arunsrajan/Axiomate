package com.github.axiomate.agentic.ide.agent;

/**
 * Callback listener interface for streaming agent responses, thinking steps, and tool execution.
 */
public interface AgentListener {

    void onToken(String token);

    void onThinking(String thought);

    void onToolCall(String toolName, String input);

    void onToolResult(String toolName, String output);

    void onComplete(String fullResponse);

    void onError(Throwable throwable);
}


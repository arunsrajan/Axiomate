package com.github.axiomate.agentic.ide.agent;

/**
 * A failure reported by the model provider or the connection to it (HTTP error status, a stream error event,
 * an unreachable server), as opposed to a bug in the IDE. The user sees it in the chat, so it is logged without
 * a stack trace.
 */
public class ProviderException extends RuntimeException {

    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}

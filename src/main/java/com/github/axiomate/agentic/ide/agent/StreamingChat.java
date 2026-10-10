package com.github.axiomate.agentic.ide.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.Response;

import java.util.List;

/**
 * A chat model that can stream its reply. The returned {@link Response} is the same as a non-streaming
 * {@code generate} call would return (text, tool calls, reasoning, usage, finish reason); the sink receives the
 * text and reasoning as they arrive.
 */
public interface StreamingChat {

    Response<AiMessage> generateStreaming(List<ChatMessage> messages, List<ToolSpecification> tools, Sink sink);

    /** Receives a reply while it is generated. Called on the request thread. */
    interface Sink {
        /** Visible answer text (not reasoning). */
        void onText(String delta);

        /** Model reasoning / thinking text, when the model streams it. */
        default void onReasoning(String delta) {
        }

        /** Polled between events; returning true stops reading and returns what arrived so far. */
        default boolean isCancelled() {
            return false;
        }

        /** The response stream being read, so a stop can close it (blocking socket reads ignore interrupts). */
        default void onStreamOpened(java.io.Closeable stream) {
        }
    }
}

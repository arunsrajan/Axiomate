package dev.langchain4j.model.anthropic.internal.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An encrypted "redacted_thinking" block that must be returned unchanged with its assistant turn.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicRedactedThinkingContent extends AnthropicMessageContent {

    public String data;

    public AnthropicRedactedThinkingContent(String data) {
        super("redacted_thinking");
        this.data = data;
    }
}

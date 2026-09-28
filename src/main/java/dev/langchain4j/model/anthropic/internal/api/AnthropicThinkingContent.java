package dev.langchain4j.model.anthropic.internal.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A "thinking" block sent back to the API inside an assistant turn. Reasoning models (Claude with extended
 * thinking, DeepSeek via the Anthropic-compatible API) expect the reasoning of a tool-calling turn to be
 * returned with that turn; without it they lose their plan and tend to repeat tool calls.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicThinkingContent extends AnthropicMessageContent {

    public String thinking;
    public String signature;

    public AnthropicThinkingContent(String thinking, String signature) {
        super("thinking");
        this.thinking = thinking;
        this.signature = signature;
    }
}

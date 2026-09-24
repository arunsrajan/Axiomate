package dev.langchain4j.model.anthropic.internal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Streaming delta block supporting text, tool calls, and thinking deltas.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AnthropicDelta {

    public String type;
    public String text;
    public String partialJson;
    public String stopReason;
    public String stopSequence;
    public String thinking;
    public String signature;
    public String data;

    public AnthropicDelta() {
    }
}

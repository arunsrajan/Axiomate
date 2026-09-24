package dev.langchain4j.model.anthropic.internal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/**
 * Anthropic content block representing text, tool_use, thinking, or image content.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AnthropicContent {

    public AnthropicContentBlockType type;
    public String text;
    public String id;
    public String name;
    public Map<String, Object> input;
    public String thinking;
    public String signature;
    public String data;

    public AnthropicContent() {
    }
}

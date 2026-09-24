package dev.langchain4j.model.anthropic.internal.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Anthropic content block types, extended to support thinking models (DeepSeek R1, DeepSeek-V4, Claude 3.7).
 */
public enum AnthropicContentBlockType {
    @JsonProperty("text")
    TEXT("text"),

    @JsonProperty("tool_use")
    TOOL_USE("tool_use"),

    @JsonProperty("thinking")
    THINKING("thinking"),

    @JsonProperty("redacted_thinking")
    REDACTED_THINKING("redacted_thinking"),

    @JsonProperty("tool_result")
    TOOL_RESULT("tool_result"),

    @JsonProperty("image")
    IMAGE("image"),

    UNKNOWN("unknown");

    private final String value;

    AnthropicContentBlockType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AnthropicContentBlockType fromString(String val) {
        if (val == null) {
            return UNKNOWN;
        }
        for (AnthropicContentBlockType type : values()) {
            if (type.value.equalsIgnoreCase(val) || type.name().equalsIgnoreCase(val)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}

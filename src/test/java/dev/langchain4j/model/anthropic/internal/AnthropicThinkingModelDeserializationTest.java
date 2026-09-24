package dev.langchain4j.model.anthropic.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContentBlockType;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that "thinking" content blocks from DeepSeek / Claude 3.7 
 * deserialize without InvalidFormatException and produce correct AiMessage output.
 */
class AnthropicThinkingModelDeserializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should deserialize 'thinking' content block type without exception")
    void shouldDeserializeThinkingContentBlockType() throws Exception {
        String json = """
                {
                  "type": "thinking"
                }
                """;
        AnthropicContent content = MAPPER.readValue(json, AnthropicContent.class);
        assertEquals(AnthropicContentBlockType.THINKING, content.type);
    }

    @Test
    @DisplayName("Should deserialize 'redacted_thinking' content block type without exception")
    void shouldDeserializeRedactedThinkingContentBlockType() throws Exception {
        String json = """
                {
                  "type": "redacted_thinking",
                  "data": "encrypted_block"
                }
                """;
        AnthropicContent content = MAPPER.readValue(json, AnthropicContent.class);
        assertEquals(AnthropicContentBlockType.REDACTED_THINKING, content.type);
        assertEquals("encrypted_block", content.data);
    }

    @Test
    @DisplayName("Should deserialize full thinking+text response and produce valid AiMessage")
    void shouldDeserializeFullThinkingAndTextResponse() throws Exception {
        String json = """
                {
                  "id": "msg_01ABC",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "thinking",
                      "thinking": "Let me analyze the problem step by step. First I need to consider..."
                    },
                    {
                      "type": "text",
                      "text": "The answer is 42."
                    }
                  ],
                  "model": "deepseek-v4-pro",
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 100,
                    "output_tokens": 200
                  }
                }
                """;
        AnthropicCreateMessageResponse response = MAPPER.readValue(json, AnthropicCreateMessageResponse.class);
        assertNotNull(response);
        assertNotNull(response.content);
        assertEquals(2, response.content.size());
        assertEquals(AnthropicContentBlockType.THINKING, response.content.get(0).type);
        assertEquals(AnthropicContentBlockType.TEXT, response.content.get(1).type);

        AiMessage aiMessage = AnthropicMapper.toAiMessage(response.content);
        assertNotNull(aiMessage);
        assertNotNull(aiMessage.text());
        // Actual AI text should only be the final answer — NOT the thinking
        assertEquals("The answer is 42.", aiMessage.text(), "AiMessage text should contain only the final answer");
        assertFalse(aiMessage.hasToolExecutionRequests());

        // Thinking is surfaced separately via LAST_THINKING thread-local
        String capturedThinking = AnthropicMapper.LAST_THINKING.get();
        AnthropicMapper.LAST_THINKING.remove();
        assertNotNull(capturedThinking, "Thinking content should be captured in LAST_THINKING");
        assertTrue(capturedThinking.contains("Let me analyze"), "Thinking should contain reasoning text");
    }

    @Test
    @DisplayName("Should produce AiMessage with only actual text when thinking block is present")
    void shouldProduceAiMessageWithOnlyThinking() throws Exception {
        String json = """
                {
                  "type": "thinking",
                  "thinking": "Internal reasoning only..."
                }
                """;
        AnthropicContent thinkingBlock = MAPPER.readValue(json, AnthropicContent.class);
        AiMessage aiMessage = AnthropicMapper.toAiMessage(List.of(thinkingBlock));
        assertNotNull(aiMessage);
        // When ONLY thinking is present, text is empty; thinking is in thread-local
        assertEquals("", aiMessage.text(), "AiMessage text should be empty when only thinking block is present");

        String capturedThinking = AnthropicMapper.LAST_THINKING.get();
        AnthropicMapper.LAST_THINKING.remove();
        assertEquals("Internal reasoning only...", capturedThinking);
    }

    @Test
    @DisplayName("Should handle unknown content block types gracefully without throwing")
    void shouldHandleUnknownContentBlockTypesGracefully() throws Exception {
        String json = """
                {
                  "type": "future_unknown_type",
                  "text": "some data"
                }
                """;
        AnthropicContent content = MAPPER.readValue(json, AnthropicContent.class);
        assertEquals(AnthropicContentBlockType.UNKNOWN, content.type);
        // Should not throw, UNKNOWN blocks are filtered in mapper
        AiMessage aiMessage = AnthropicMapper.toAiMessage(List.of(content));
        assertNotNull(aiMessage);
    }

    @Test
    @DisplayName("Should deserialize 'text' and 'tool_use' blocks unchanged from original behaviour")
    void shouldDeserializeTextAndToolUseBlocksAsOriginal() throws Exception {
        assertEquals(AnthropicContentBlockType.TEXT, AnthropicContentBlockType.fromString("text"));
        assertEquals(AnthropicContentBlockType.TOOL_USE, AnthropicContentBlockType.fromString("tool_use"));
        assertEquals(AnthropicContentBlockType.THINKING, AnthropicContentBlockType.fromString("thinking"));
        assertEquals(AnthropicContentBlockType.REDACTED_THINKING, AnthropicContentBlockType.fromString("redacted_thinking"));
        assertEquals(AnthropicContentBlockType.UNKNOWN, AnthropicContentBlockType.fromString("something_new"));
        assertEquals(AnthropicContentBlockType.UNKNOWN, AnthropicContentBlockType.fromString(null));
    }
}

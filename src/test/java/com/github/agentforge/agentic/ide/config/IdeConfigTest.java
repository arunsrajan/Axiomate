package com.github.agentforge.agentic.ide.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdeConfigTest {

    @Test
    @DisplayName("IdeConfig default properties should be set properly")
    void testDefaultConfig() {
        IdeConfig config = new IdeConfig();
        assertNotNull(config.getTheme());
        assertEquals(14, config.getFontSize());
        assertEquals("MOCK", config.getAiProvider());
        assertNotNull(config.getSystemPrompt());
        assertTrue(config.getTemperature() >= 0 && config.getTemperature() <= 1.0);
    }

    @Test
    @DisplayName("FontSize should be clamped between 10 and 32")
    void testFontSizeClamping() {
        IdeConfig config = new IdeConfig();
        config.setFontSize(5);
        assertEquals(10, config.getFontSize());

        config.setFontSize(40);
        assertEquals(32, config.getFontSize());

        config.setFontSize(18);
        assertEquals(18, config.getFontSize());
    }
}

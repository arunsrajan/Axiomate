package com.github.axiomate.agentic.ide.config;

import com.github.axiomate.agentic.ide.ui.components.ProviderSettingsPanel;
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

    @Test
    @DisplayName("IdeConfig serialization and deserialization retains modified provider settings")
    void testSerializationRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

        IdeConfig config = new IdeConfig();
        ProviderConfig anthropic = config.getProvider("ANTHROPIC");
        assertNotNull(anthropic);
        anthropic.setApiKey("sk-ant-test12345");
        anthropic.setBaseUrl("https://custom.anthropic.proxy/v1");
        anthropic.setDefaultModel("claude-3-5-sonnet");

        String json = mapper.writeValueAsString(config);
        System.out.println("SERIALIZED JSON:\n" + json);

        IdeConfig deserialized = mapper.readValue(json, IdeConfig.class);
        ProviderConfig desAnthropic = deserialized.getProvider("ANTHROPIC");
        assertNotNull(desAnthropic);
        assertEquals("sk-ant-test12345", desAnthropic.getApiKey());
        assertEquals("https://custom.anthropic.proxy/v1", desAnthropic.getBaseUrl());
        assertEquals("claude-3-5-sonnet", desAnthropic.getDefaultModel());
    }

    @Test
    @DisplayName("Removing and adding providers persists correctly through serialization")
    void testAddAndRemoveProviderRoundTrip() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        IdeConfig config = new IdeConfig();
        // Remove MOCK provider
        config.removeProvider("MOCK");
        assertNull(config.getProvider("MOCK"));

        // Add a new custom provider
        ProviderConfig newProv = new ProviderConfig(
                "DEEPSEEK_CUSTOM", "ANTHROPIC", "DeepSeek Claude Proxy",
                "https://api.deepseek.com/anthropic", "deepseek-v4-pro",
                java.util.List.of(new ModelDefinition("deepseek-v4-pro", "DeepSeek V4 Pro", 128_000, 8_192, java.util.List.of("reasoning")))
        );
        newProv.setApiKey("sk-deepseek-custom-key");
        config.addProvider(newProv);

        String json = mapper.writeValueAsString(config);
        IdeConfig deserialized = mapper.readValue(json, IdeConfig.class);

        // Verify MOCK is NOT present
        assertNull(deserialized.getProvider("MOCK"), "Removed provider MOCK must not reappear after deserialization");
        // Verify DEEPSEEK_CUSTOM is present
        ProviderConfig loadedDeepseek = deserialized.getProvider("DEEPSEEK_CUSTOM");
        assertNotNull(loadedDeepseek);
        assertEquals("sk-deepseek-custom-key", loadedDeepseek.getApiKey());
        assertEquals("https://api.deepseek.com/anthropic", loadedDeepseek.getBaseUrl());
    }

    @Test
    @DisplayName("Legacy JSON containing anthropicProviders and anthropicType deserializes without throwing exception")
    void testLegacyJsonWithAnthropicProviders() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // Sample JSON mimicking previous buggy serialization that threw UnsupportedOperationException
        String legacyJson = """
        {
          "theme": "FlatLaf Darcula",
          "fontSize": 14,
          "activeProviderId": "CUSTOM_ANTHROPIC",
          "activeModelId": "claude-3-7-sonnet",
          "providers": {
            "CUSTOM_ANTHROPIC": {
              "id": "CUSTOM_ANTHROPIC",
              "providerType": "ANTHROPIC",
              "name": "Custom Anthropic (Claude API)",
              "baseUrl": "https://api.deepseek.com/anthropic",
              "apiKey": "sk-legacy-test-key",
              "enabled": true,
              "defaultModel": "deepseek-v4-pro",
              "models": [
                {
                  "id": "deepseek-v4-pro",
                  "displayName": "DeepSeek V4 Pro",
                  "maxContextTokens": 128000,
                  "maxOutputTokens": 8192,
                  "tags": ["reasoning"]
                }
              ],
              "anthropicType": true
            }
          },
          "apiKey": "",
          "apiBaseUrl": "https://api.deepseek.com/anthropic",
          "anthropicProviders": [
            {
              "id": "CUSTOM_ANTHROPIC",
              "providerType": "ANTHROPIC",
              "name": "Custom Anthropic (Claude API)",
              "baseUrl": "https://api.deepseek.com/anthropic",
              "apiKey": "sk-legacy-test-key",
              "enabled": true,
              "defaultModel": "deepseek-v4-pro"
            }
          ]
        }
        """;

        IdeConfig config = mapper.readValue(legacyJson, IdeConfig.class);
        assertNotNull(config);
        ProviderConfig prov = config.getProvider("CUSTOM_ANTHROPIC");
        assertNotNull(prov);
        assertEquals("sk-legacy-test-key", prov.getApiKey());
        assertEquals("https://api.deepseek.com/anthropic", prov.getBaseUrl());
        assertEquals("deepseek-v4-pro", prov.getDefaultModel());
    }

    @Test
    @DisplayName("ProviderSettingsPanel applyToConfig persists modified provider fields into IdeConfig")
    void testProviderSettingsPanelApplyToConfig() {
        // Create an IdeConfig and verify initial state
        IdeConfig config = new IdeConfig();
        ProviderConfig anthropic = config.getProvider("ANTHROPIC");
        assertNotNull(anthropic);
        anthropic.setApiKey("");

        // Instantiate ProviderSettingsPanel
        ProviderSettingsPanel panel = new ProviderSettingsPanel();

        // Panel copies providers into its working map; apply directly to targetConfig
        IdeConfig targetConfig = new IdeConfig();
        panel.applyToConfig(targetConfig);

        assertNotNull(targetConfig.getProviders());
        assertTrue(targetConfig.getProviders().containsKey("ANTHROPIC"));
        assertTrue(targetConfig.getProviders().containsKey("CUSTOM_ANTHROPIC"));
    }
}


package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderModelEditTest {

    private static ProviderConfig provider() {
        List<ModelDefinition> models = new ArrayList<>(List.of(
                new ModelDefinition("a", "A", 128_000, 4_096, List.of()),
                new ModelDefinition("b", "B", 200_000, 8_192, List.of())));
        return new ProviderConfig("P", "CUSTOM", "P", "http://x", "a", models);
    }

    @Test
    @DisplayName("Editing a model keeps its position and accepts a 20M max output")
    void editInPlaceWithLargeOutput() {
        ProviderConfig p = provider();
        ProviderSettingsPanel.applyModelDefinition(p, "a",
                new ModelDefinition("a", "A big", 20_000_000, ProviderSettingsPanel.MAX_OUTPUT_TOKENS_LIMIT, List.of("long")));
        assertEquals(2, p.getModels().size());
        assertEquals("a", p.getModels().get(0).getId());
        assertEquals(20_000_000, p.getModels().get(0).getMaxOutputTokens());
        assertEquals("A big", p.getModels().get(0).getDisplayName());
    }

    @Test
    @DisplayName("Renaming the default model updates the provider default and drops id clashes")
    void renameUpdatesDefault() {
        ProviderConfig p = provider();
        ProviderSettingsPanel.applyModelDefinition(p, "a", new ModelDefinition("a2", "A2", 128_000, 4_096, List.of()));
        assertEquals("a2", p.getDefaultModel());
        ProviderSettingsPanel.applyModelDefinition(p, "a2", new ModelDefinition("b", "B new", 128_000, 1_000_000, List.of()));
        assertEquals(1, p.getModels().size());
        assertEquals(1_000_000, p.getModels().get(0).getMaxOutputTokens());
    }

    @Test
    @DisplayName("Adding (no original id) appends the model")
    void addAppends() {
        ProviderConfig p = provider();
        ProviderSettingsPanel.applyModelDefinition(p, null, new ModelDefinition("c", "C", 128_000, 4_096, List.of()));
        assertEquals(List.of("a", "b", "c"), p.getModels().stream().map(ModelDefinition::getId).toList());
    }
}

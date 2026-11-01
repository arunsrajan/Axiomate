package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

/** Settings → AI Providers: the three context checkboxes follow the selected provider and are saved with it. */
class ContextOptionsSettingsTest {

    @SuppressWarnings("unchecked")
    private static JComboBox<String> providerSelector(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JComboBox<?> box) {
                for (int i = 0; i < box.getItemCount(); i++) {
                    if (IdeConfig.COLIBRI_PROVIDER_ID.equals(box.getItemAt(i))) return (JComboBox<String>) box;
                }
            }
            if (k instanceof Container cc) {
                JComboBox<String> found = providerSelector(cc);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Colibri shows all three ticked, other providers none; unticking one is saved for that provider only")
    void checkboxes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ProviderSettingsPanel panel = new ProviderSettingsPanel();
            JComboBox<String> selector = providerSelector(panel);
            assertNotNull(selector, "the Colibri preset is listed");

            selector.setSelectedItem(IdeConfig.COLIBRI_PROVIDER_ID);
            assertTrue(panel.compactResultsCheck.isSelected());
            assertTrue(panel.capOutputsCheck.isSelected());
            assertTrue(panel.stablePrefixCheck.isSelected());
            panel.capOutputsCheck.setSelected(false);

            selector.setSelectedItem("OPENAI");
            assertFalse(panel.compactResultsCheck.isSelected() || panel.capOutputsCheck.isSelected()
                    || panel.stablePrefixCheck.isSelected(), "other providers: off, working as before");

            IdeConfig target = new IdeConfig();
            panel.applyToConfig(target);
            ProviderConfig colibri = target.getProvider(IdeConfig.COLIBRI_PROVIDER_ID);
            assertTrue(colibri.isCompactToolResults());
            assertFalse(colibri.isCapToolOutputs(), "the unticked box is saved");
            assertTrue(colibri.isStablePromptPrefix());
            assertFalse(target.getProvider("OPENAI").isCapToolOutputs());
        });
    }
}

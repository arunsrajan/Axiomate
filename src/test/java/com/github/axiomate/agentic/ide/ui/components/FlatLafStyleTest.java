package com.github.axiomate.agentic.ide.ui.components;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FlatLaf rejects unknown style keys at runtime (logged as SEVERE, not thrown), so building the agent panel
 * under FlatLaf must not log any style errors.
 */
class FlatLafStyleTest {

    private final LookAndFeel original = UIManager.getLookAndFeel();

    @AfterEach
    void restore() throws Exception {
        UIManager.setLookAndFeel(original);
    }

    @Test
    void agentPanelStylesAreValidInDarkAndLight() throws Exception {
        for (LookAndFeel laf : List.of(new FlatDarkLaf(), new FlatLightLaf())) {
            UIManager.setLookAndFeel(laf);
            List<String> errors = new ArrayList<>();
            Handler h = new Handler() {
                @Override
                public void publish(LogRecord r) {
                    if (r.getLevel().intValue() >= Level.SEVERE.intValue()) {
                        errors.add(r.getThrown() != null ? r.getThrown().toString() : r.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            Logger log = Logger.getLogger("com.formdev.flatlaf");
            log.addHandler(h);
            try {
                new AIAgentPanel(() -> "", new TerminalPanel());
            } finally {
                log.removeHandler(h);
            }
            assertEquals(List.of(), errors, "FlatLaf style errors under " + laf.getName());
        }
    }
}

package com.github.axiomate.agentic.ide;

import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.ui.MainFrame;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

/**
 * Main application launcher for the Axiomate AI Agent IDE.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Axiomate AI Agent IDE (com.github.axiomate.agentic.ide)...");

        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        ConfigManager configManager = ConfigManager.getInstance();
        IdeConfig config = configManager.getConfig();

        UIUtils.applyTheme(config.getTheme(), null);

        // Pre-initialize Agentic Memory & Tools
        MemoryManager.getInstance();
        AgentManager.getInstance();

        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
                log.info("Axiomate AI Agent IDE initialized successfully.");
            } catch (Exception e) {
                log.error("Fatal error during IDE startup", e);
                JOptionPane.showMessageDialog(null, "Error launching IDE: " + e.getMessage(), "Startup Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}


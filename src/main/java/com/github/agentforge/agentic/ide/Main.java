package com.github.agentforge.agentic.ide;

import com.github.agentforge.agentic.ide.agent.AgentManager;
import com.github.agentforge.agentic.ide.agent.memory.MemoryManager;
import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.ui.MainFrame;
import com.github.agentforge.agentic.ide.ui.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

/**
 * Main application launcher for the AgentForge AI Agent IDE.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting AgentForge AI Agent IDE (com.github.agentforge.agentic.ide)...");

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
                log.info("AgentForge AI Agent IDE initialized successfully.");
            } catch (Exception e) {
                log.error("Fatal error during IDE startup", e);
                JOptionPane.showMessageDialog(null, "Error launching IDE: " + e.getMessage(), "Startup Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}

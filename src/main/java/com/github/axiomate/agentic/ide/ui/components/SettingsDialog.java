package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * Settings configuration dialog supporting AI Provider settings (Anthropic, OpenAI, Gemini, Custom URLs),
 * Multiple Models per Provider, Task-Based Routing, MCP servers, and IDE appearance.
 */
public class SettingsDialog extends JDialog {

    private final ProviderSettingsPanel providerSettingsPanel;
    private final McpSettingsPanel mcpSettingsPanel;
    private final JTabbedPane tabbedPane;

    private final JSlider tempSlider;
    private final JTextArea systemPromptArea;
    private final JComboBox<String> themeCombo;
    private final JSpinner fontSizeSpinner;

    public SettingsDialog(JFrame parent) {
        this(parent, 0);
    }

    public SettingsDialog(JFrame parent, int initialTabIndex) {
        super(parent, "IDE & AI Settings (Providers, Routing, MCP & Memory)", true);
        setSize(780, 720);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        IdeConfig config = ConfigManager.getInstance().getConfig();

        // TAB 1: AI Providers & Task-Based Model Routing
        providerSettingsPanel = new ProviderSettingsPanel();
        tabbedPane.addTab("AI Providers & Routing", providerSettingsPanel);

        // TAB 2: General & Editor Appearance
        JPanel generalPanel = new JPanel();
        generalPanel.setLayout(new BoxLayout(generalPanel, BoxLayout.Y_AXIS));
        generalPanel.setBorder(new EmptyBorder(12, 16, 12, 16));

        // System Prompt
        JPanel promptPanel = new JPanel(new BorderLayout(4, 4));
        promptPanel.setBorder(new CompoundBorder(new TitledBorder("Global Agent System Prompt"), new EmptyBorder(6, 6, 6, 6)));
        systemPromptArea = new JTextArea(config.getSystemPrompt(), 4, 30);
        systemPromptArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        promptPanel.add(new JScrollPane(systemPromptArea), BorderLayout.CENTER);
        generalPanel.add(promptPanel);
        generalPanel.add(Box.createVerticalStrut(10));

        // Temperature
        JPanel tempPanel = new JPanel(new GridBagLayout());
        tempPanel.setBorder(new CompoundBorder(new TitledBorder("Model Creativity / Sampling"), new EmptyBorder(8, 8, 8, 8)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.3;
        tempPanel.add(new JLabel("Temperature:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.7;
        tempSlider = new JSlider(0, 100, (int) (config.getTemperature() * 100));
        tempSlider.setMajorTickSpacing(25);
        tempSlider.setPaintTicks(true);
        tempSlider.setPaintLabels(true);
        tempPanel.add(tempSlider, gbc);
        generalPanel.add(tempPanel);
        generalPanel.add(Box.createVerticalStrut(10));

        // UI Appearance
        JPanel uiPanel = new JPanel(new GridBagLayout());
        uiPanel.setBorder(new CompoundBorder(new TitledBorder("Appearance & Editor"), new EmptyBorder(8, 8, 8, 8)));

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.3;
        uiPanel.add(new JLabel("Color Theme:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.7;
        themeCombo = new JComboBox<>(new String[]{"FlatLaf Darcula", "FlatLaf Dark", "FlatLaf Light", "IntelliJ Light", "One Dark"});
        themeCombo.setSelectedItem(config.getTheme());
        uiPanel.add(themeCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.3;
        uiPanel.add(new JLabel("Font Size:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.7;
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(config.getFontSize(), 10, 32, 1));
        uiPanel.add(fontSizeSpinner, gbc);

        generalPanel.add(uiPanel);
        tabbedPane.addTab("Editor & Appearance", new JScrollPane(generalPanel));

        // TAB 3: Model Context Protocol (MCP) Servers
        mcpSettingsPanel = new McpSettingsPanel();
        tabbedPane.addTab("Model Context Protocol (MCP)", mcpSettingsPanel);

        if (initialTabIndex >= 0 && initialTabIndex < tabbedPane.getTabCount()) {
            tabbedPane.setSelectedIndex(initialTabIndex);
        }

        add(tabbedPane, BorderLayout.CENTER);

        // Action Buttons at bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        JButton cancelBtn = new JButton("Close");
        cancelBtn.addActionListener(e -> dispose());

        JButton saveBtn = UIUtils.createPillButton("Save Settings", null, UIUtils.ACCENT_COLOR, Color.WHITE);
        saveBtn.addActionListener(e -> saveSettings(parent));

        buttonPanel.add(cancelBtn);
        buttonPanel.add(saveBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void saveSettings(JFrame parent) {
        IdeConfig config = ConfigManager.getInstance().getConfig();

        // 1. Apply provider settings and task routings
        providerSettingsPanel.applyToConfig(config);

        // 2. Apply editor & appearance settings
        config.setTemperature(tempSlider.getValue() / 100.0);
        config.setSystemPrompt(systemPromptArea.getText().trim());

        String newTheme = (String) themeCombo.getSelectedItem();
        config.setTheme(newTheme);
        config.setFontSize((Integer) fontSizeSpinner.getValue());

        ConfigManager.getInstance().saveConfig(config);
        com.github.axiomate.agentic.ide.agent.AgentManager.getInstance().updateActiveService(config);
        UIUtils.applyTheme(newTheme, parent);

        dispose();
    }
}


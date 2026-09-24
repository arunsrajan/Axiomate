package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Top action toolbar providing rapid access to file actions, execution, and quick AI prompts.
 */
public class ToolBar extends JToolBar {

    private final JComboBox<String> providerCombo;
    private final JTextField quickAiInput;

    public ToolBar(Runnable onNewFile,
                   Runnable onOpenFile,
                   Runnable onSaveFile,
                   Runnable onRunFile,
                   Runnable onImportMemory,
                   Consumer<String> onQuickAiPrompt,
                   Runnable onOpenSettings) {
        setFloatable(false);
        setRollover(true);
        setBorder(new EmptyBorder(4, 8, 4, 8));

        // File buttons
        JButton newBtn = createToolButton("New", UIUtils.createFileIcon(16, null), e -> onNewFile.run());
        JButton openBtn = createToolButton("Open", UIUtils.createFolderIcon(16, null), e -> onOpenFile.run());
        JButton saveBtn = createToolButton("Save", null, e -> onSaveFile.run());
        saveBtn.setText("💾 Save");

        add(newBtn);
        add(openBtn);
        add(saveBtn);
        addSeparator(new Dimension(12, 24));

        // Run button
        JButton runBtn = createToolButton("Run File", UIUtils.createPlayIcon(16, UIUtils.SUCCESS_COLOR), e -> onRunFile.run());
        runBtn.setText(" Run");
        runBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        add(runBtn);

        addSeparator(new Dimension(12, 24));

        // Import Memory Button
        JButton memBtn = createToolButton("Import All Memory", null, e -> onImportMemory.run());
        memBtn.setText("🧠 Import Memory");
        memBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        add(memBtn);

        addSeparator(new Dimension(16, 24));

        // Quick AI Prompt box in toolbar
        JLabel aiLabel = new JLabel(UIUtils.createSparkleIcon(16, UIUtils.ACCENT_PURPLE));
        quickAiInput = new JTextField(20);
        quickAiInput.setFont(new Font("SansSerif", Font.PLAIN, 12));
        quickAiInput.putClientProperty("JTextField.placeholderText", "Ask Axiomate AI...");
        quickAiInput.addActionListener(e -> {
            String text = quickAiInput.getText().trim();
            if (!text.isEmpty()) {
                quickAiInput.setText("");
                onQuickAiPrompt.accept(text);
            }
        });

        JButton askBtn = UIUtils.createPillButton("Ask", UIUtils.createSparkleIcon(12, Color.WHITE),
                UIUtils.ACCENT_PURPLE, Color.WHITE);
        askBtn.addActionListener(e -> {
            String text = quickAiInput.getText().trim();
            if (!text.isEmpty()) {
                quickAiInput.setText("");
                onQuickAiPrompt.accept(text);
            }
        });

        add(aiLabel);
        add(Box.createHorizontalStrut(6));
        add(quickAiInput);
        add(Box.createHorizontalStrut(4));
        add(askBtn);

        add(Box.createHorizontalGlue());

        // Model provider selector
        JLabel provLabel = new JLabel("Provider: ");
        provLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        providerCombo = new JComboBox<>(new String[]{"Mock Simulator (Offline)", "OpenAI (Cloud)", "Custom / Ollama"});
        providerCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        providerCombo.setFocusable(false);

        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        if ("OPENAI".equalsIgnoreCase(cfg.getAiProvider())) {
            providerCombo.setSelectedIndex(1);
        } else if ("CUSTOM".equalsIgnoreCase(cfg.getAiProvider())) {
            providerCombo.setSelectedIndex(2);
        } else {
            providerCombo.setSelectedIndex(0);
        }

        providerCombo.addActionListener(e -> {
            IdeConfig cur = ConfigManager.getInstance().getConfig();
            int idx = providerCombo.getSelectedIndex();
            if (idx == 1) {
                cur.setAiProvider("OPENAI");
            } else if (idx == 2) {
                cur.setAiProvider("CUSTOM");
            } else {
                cur.setAiProvider("MOCK");
            }
            ConfigManager.getInstance().saveConfig(cur);
        });

        add(provLabel);
        add(providerCombo);
        addSeparator(new Dimension(8, 24));

        JButton settingsBtn = createToolButton("Settings", UIUtils.createGearIcon(16, null), e -> onOpenSettings.run());
        add(settingsBtn);
    }

    private JButton createToolButton(String tooltip, Icon icon, java.awt.event.ActionListener listener) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(listener);
        return btn;
    }
}


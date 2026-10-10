package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Top action toolbar providing rapid access to file actions, execution, agent sync, plugins,
 * the command palette and quick AI prompts.
 */
public class ToolBar extends JToolBar {

    private final JComboBox<String> providerCombo;
    private final JTextField quickAiInput;
    private boolean updatingToolbar = false;

    public ToolBar(Runnable onNewFile,
                   Runnable onOpenFile,
                   Runnable onSaveFile,
                   Runnable onRunFile,
                   Runnable onImportMemory,
                   Consumer<String> onQuickAiPrompt,
                   Runnable onOpenSettings) {
        this(onNewFile, onOpenFile, onSaveFile, onRunFile, onImportMemory, onQuickAiPrompt, onOpenSettings, IdeActions.NONE);
    }

    public ToolBar(Runnable onNewFile,
                   Runnable onOpenFile,
                   Runnable onSaveFile,
                   Runnable onRunFile,
                   Runnable onImportMemory,
                   Consumer<String> onQuickAiPrompt,
                   Runnable onOpenSettings,
                   IdeActions actions) {
        setFloatable(false);
        setRollover(true);
        applyColors();
        UIUtils.addThemeListener(this::applyColors);

        add(createToolButton("New file (Ctrl+N)", UIUtils.createFileIcon(16, null), e -> onNewFile.run()));
        add(createToolButton("Open file (Ctrl+O)", UIUtils.createFolderIcon(16, null), e -> onOpenFile.run()));
        JButton saveBtn = createToolButton("Save (Ctrl+S)", UIUtils.glyph(UIUtils.Glyph.DOWNLOAD, 16, null), e -> onSaveFile.run());
        add(saveBtn);
        addSeparator(new Dimension(10, 24));

        JButton runBtn = createToolButton("Run active file (Shift+F10)", UIUtils.createPlayIcon(16, UIUtils.SUCCESS_COLOR), e -> onRunFile.run());
        runBtn.setText("Run");
        add(runBtn);
        addSeparator(new Dimension(10, 24));

        // Agent Sync dropdown: memory, MCP servers and sessions from other coding agents
        JButton syncBtn = createToolButton("Import/export memory, MCP servers and sessions with Claude Code, Codex, Cursor, Antigravity…",
                UIUtils.glyph(UIUtils.Glyph.SYNC, 16, UIUtils.ACCENT_PURPLE), null);
        syncBtn.setText("Agent Sync ▾");
        syncBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Import memory from coding agents…", actions::openMemoryImport));
            menu.add(item("Export memory to coding agents…", actions::openMemoryExport));
            menu.add(item("Import memory from file…", onImportMemory));
            menu.addSeparator();
            menu.add(item("Import MCP servers from agents…", () -> actions.openMcpInterop(false)));
            menu.add(item("Export MCP servers to agents…", () -> actions.openMcpInterop(true)));
            menu.addSeparator();
            menu.add(item("Import sessions from Claude Code / Codex…", actions::openExternalSessionImport));
            menu.add(item("Show Agent Sync panel", () -> actions.showSidebarView(IdeActions.VIEW_AGENT_SYNC)));
            menu.show(syncBtn, 0, syncBtn.getHeight());
        });
        add(syncBtn);

        JButton pluginsBtn = createToolButton("Plugins (Ctrl+Shift+X)", UIUtils.glyph(UIUtils.Glyph.PLUGINS, 16, UIUtils.ACCENT_COLOR),
                e -> actions.openPluginManager(0));
        pluginsBtn.setText("Plugins");
        add(pluginsBtn);

        addSeparator(new Dimension(14, 24));

        // Quick AI Prompt box in toolbar
        JLabel aiLabel = new JLabel(UIUtils.createSparkleIcon(16, UIUtils.ACCENT_PURPLE));
        quickAiInput = new JTextField(20);
        quickAiInput.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        quickAiInput.putClientProperty("JTextField.placeholderText", "Ask Axiomate AI…");
        quickAiInput.setToolTipText("Quick prompt for the active agent session — supports /slash commands");
        quickAiInput.setMaximumSize(new Dimension(360, 30));
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

        add(createToolButton("Command palette (Ctrl+K / F1)", UIUtils.glyph(UIUtils.Glyph.COMMAND, 16, null),
                e -> actions.openCommandPalette()));
        addSeparator(new Dimension(8, 24));

        // Model provider selector
        JLabel provLabel = new JLabel("Provider: ");
        provLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        providerCombo = new JComboBox<>();
        providerCombo.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        providerCombo.setFocusable(false);
        providerCombo.setMaximumSize(new Dimension(180, 30));

        refreshProviderCombo(ConfigManager.getInstance().getConfig());

        providerCombo.addActionListener(e -> {
            if (updatingToolbar) return;
            IdeConfig cur = ConfigManager.getInstance().getConfig();
            String selected = (String) providerCombo.getSelectedItem();
            if (selected != null && cur.getProviders().containsKey(selected)) {
                cur.setActiveProviderId(selected);
                ConfigManager.getInstance().saveConfig(cur);
            }
        });

        ConfigManager.getInstance().addListener(cfg -> UIUtils.onEdt(() -> refreshProviderCombo(cfg)));

        add(provLabel);
        add(providerCombo);
        addSeparator(new Dimension(8, 24));

        add(createToolButton("Settings (Ctrl+,)", UIUtils.createGearIcon(16, null), e -> onOpenSettings.run()));
    }

    private void applyColors() {
        setBackground(UIUtils.surface(2));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtils.borderColor()),
                new EmptyBorder(4, 8, 4, 8)));
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    private void refreshProviderCombo(IdeConfig cfg) {
        if (providerCombo == null || cfg == null) return;
        updatingToolbar = true;
        try {
            providerCombo.removeAllItems();
            for (String pId : cfg.getProviders().keySet()) {
                providerCombo.addItem(pId);
            }
            if (cfg.getProviders().containsKey(cfg.getActiveProviderId())) {
                providerCombo.setSelectedItem(cfg.getActiveProviderId());
            }
        } finally {
            updatingToolbar = false;
        }
    }

    private JButton createToolButton(String tooltip, Icon icon, java.awt.event.ActionListener listener) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (listener != null) btn.addActionListener(listener);
        return btn;
    }
}

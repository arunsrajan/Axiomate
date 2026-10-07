package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.plugins.InstalledPlugin;
import com.github.axiomate.agentic.ide.plugins.PluginManager;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Sidebar view listing installed plugins with quick enable/disable toggles and shortcuts to the
 * marketplace and install-from-folder/ZIP/URL flows.
 */
public class PluginsPanel extends JPanel {

    private final JPanel listPanel = new JPanel();
    private final JLabel footer = new JLabel();
    private final PluginManager pluginManager;

    public PluginsPanel(IdeActions actions, PluginManager pluginManager) {
        super(new BorderLayout());
        IdeActions a = actions != null ? actions : IdeActions.NONE;
        this.pluginManager = pluginManager;

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(8, 10, 4, 6));
        header.add(UIUtils.sectionHeader("Plugins"), BorderLayout.CENTER);
        header.add(UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.MORE, 16, null), "Plugin Manager", e -> a.openPluginManager(1)),
                BorderLayout.EAST);

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
        buttons.setOpaque(false);
        buttons.setBorder(new EmptyBorder(2, 10, 10, 10));
        JButton market = new JButton("Browse marketplace", UIUtils.glyph(UIUtils.Glyph.PLUGINS, 14, Color.WHITE));
        market.putClientProperty("JButton.buttonType", "default");
        market.setBackground(UIUtils.ACCENT_COLOR);
        market.setForeground(Color.WHITE);
        market.setFocusable(false);
        market.addActionListener(e -> a.openPluginManager(0));
        JButton install = new JButton("Install from folder, ZIP or URL…", UIUtils.glyph(UIUtils.Glyph.DOWNLOAD, 14, null));
        install.setFocusable(false);
        install.addActionListener(e -> a.openPluginManager(2));
        buttons.add(market);
        buttons.add(install);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.CENTER);
        JLabel installedHeader = UIUtils.sectionHeader("Installed");
        installedHeader.setBorder(new EmptyBorder(0, 10, 4, 10));
        north.add(installedHeader, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        JPanel wrap = new ScrollablePanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(listPanel, BorderLayout.NORTH);
        JScrollPane scroll = ScrollablePanel.verticalScroll(wrap);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        footer.setBorder(new EmptyBorder(4, 10, 6, 10));
        footer.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        add(footer, BorderLayout.SOUTH);

        pluginManager.addChangeListener(() -> SwingUtilities.invokeLater(this::refresh));
        SlashCommandRegistry.getInstance().addChangeListener(() -> SwingUtilities.invokeLater(this::updateFooter));
        UIUtils.addThemeListener(this::applyColors);
        applyColors();
        refresh();
    }

    private void applyColors() {
        setBackground(UIUtils.surface(1));
        footer.setForeground(UIUtils.mutedForeground());
    }

    public void refresh() {
        listPanel.removeAll();
        var installed = pluginManager.getInstalled();
        if (installed.isEmpty()) {
            JLabel empty = new JLabel("<html>No plugins yet. Browse the marketplace for MCP servers, rule packs and "
                    + "workflow commands, or install a Claude Code plugin from a folder, ZIP or GitHub URL.</html>");
            empty.setForeground(UIUtils.mutedForeground());
            empty.setBorder(new EmptyBorder(6, 12, 6, 12));
            listPanel.add(empty);
        }
        for (InstalledPlugin p : installed) {
            listPanel.add(row(p));
        }
        updateFooter();
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void updateFooter() {
        long enabled = pluginManager.getInstalled().stream().filter(InstalledPlugin::enabled).count();
        footer.setText(enabled + " enabled · " + SlashCommandRegistry.getInstance().all().size() + " slash commands available");
    }

    private JComponent row(InstalledPlugin p) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(5, 12, 5, 8));
        JLabel name = new JLabel(p.manifest().displayName());
        name.setFont(UIUtils.uiFont(Font.BOLD, 12.5f));
        name.setForeground(p.enabled() ? UIUtils.foreground() : UIUtils.mutedForeground());
        JLabel meta = new JLabel(p.manifest().contributionSummary()
                + (p.mcpServerNames().isEmpty() ? "" : (p.mcpEnabled() ? " · running" : " · MCP stopped")));
        meta.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        meta.setForeground(UIUtils.mutedForeground());
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(meta);
        row.add(text, BorderLayout.CENTER);

        JCheckBox toggle = new JCheckBox();
        toggle.setSelected(p.enabled());
        toggle.setOpaque(false);
        toggle.setToolTipText(p.enabled() ? "Disable plugin" : "Enable plugin");
        toggle.putClientProperty("JComponent.sizeVariant", "small");
        toggle.addActionListener(e -> pluginManager.setEnabled(p.id(), toggle.isSelected()));
        row.add(toggle, BorderLayout.EAST);
        row.setToolTipText("<html><b>" + p.manifest().displayName() + "</b> v" + p.manifest().version() + "<br>"
                + p.manifest().description() + "</html>");
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        return row;
    }
}

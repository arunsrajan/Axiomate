package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.plugins.InstalledPlugin;
import com.github.axiomate.agentic.ide.plugins.PluginCatalog;
import com.github.axiomate.agentic.ide.plugins.PluginManager;
import com.github.axiomate.agentic.ide.plugins.PluginManager.InstallOptions;
import com.github.axiomate.agentic.ide.plugins.PluginManifest;
import com.github.axiomate.agentic.ide.plugins.PluginPackageReader.PluginPackage;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plugin Manager: browse the built-in marketplace, manage installed plugins, install plugins from a folder,
 * ZIP or URL (Axiomate or Claude Code format), and review every available slash command.
 */
public class PluginManagerDialog extends JDialog {

    public static final int TAB_MARKETPLACE = 0;
    public static final int TAB_INSTALLED = 1;
    public static final int TAB_INSTALL_FROM = 2;
    public static final int TAB_COMMANDS = 3;

    private final PluginManager manager;
    private final Path projectRoot;
    private final IdeActions actions;
    private final JPanel cards = new JPanel();
    private final JTextField marketSearch = new JTextField();
    private final JComboBox<String> category = new JComboBox<>(new String[]{"All categories",
            PluginCatalog.CATEGORY_MCP, PluginCatalog.CATEGORY_RULES, PluginCatalog.CATEGORY_WORKFLOW});
    private final InstalledTableModel installedModel = new InstalledTableModel();
    private final JTable installedTable = new JTable(installedModel);
    private final JTextArea installedDetails = DialogSupport.previewArea();
    private final CommandsTableModel commandsModel = new CommandsTableModel();
    private final Runnable changeListener = () -> SwingUtilities.invokeLater(this::refreshAll);

    public PluginManagerDialog(Window owner, PluginManager manager, Path projectRoot, IdeActions actions, int initialTab) {
        super(owner, "Plugin Manager", ModalityType.APPLICATION_MODAL);
        this.manager = manager;
        this.projectRoot = projectRoot;
        this.actions = actions != null ? actions : IdeActions.NONE;
        setSize(1000, 700);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
        add(DialogSupport.banner("Plugins",
                "Extend Axiomate with MCP servers, rule packs and workflow commands. Claude Code plugins "
                        + "(commands, agents, skills, .mcp.json) install directly."), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Marketplace", marketplaceTab());
        tabs.addTab("Installed", installedTab());
        tabs.addTab("Install from…", installFromTab());
        tabs.addTab("Commands", commandsTab());
        tabs.setSelectedIndex(Math.max(0, Math.min(initialTab, tabs.getTabCount() - 1)));
        add(tabs, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JLabel location = new JLabel("Installed plugins live in ~/.axiomate-ide/plugins");
        location.setToolTipText(manager.getPluginsRoot().toString());
        location.setForeground(UIUtils.mutedForeground());
        add(DialogSupport.buttonBar(location, close), BorderLayout.SOUTH);
        DialogSupport.closeOnEscape(this);

        manager.addChangeListener(changeListener);
        refreshAll();
    }

    private void refreshAll() {
        renderCards();
        InstalledPlugin keep = installedModel.get(installedTable.getSelectedRow());
        installedModel.setRows(manager.getInstalled());
        if (keep != null) {
            for (int i = 0; i < installedModel.getRowCount(); i++) {
                if (installedModel.get(i).id().equals(keep.id())) installedTable.setRowSelectionInterval(i, i);
            }
        }
        commandsModel.setRows(SlashCommandRegistry.getInstance().all());
    }

    // ------------------------------------------------------------------
    // Marketplace
    // ------------------------------------------------------------------

    private JComponent marketplaceTab() {
        marketSearch.putClientProperty("JTextField.placeholderText", "Search plugins…");
        marketSearch.putClientProperty("JTextField.leadingIcon", UIUtils.glyph(UIUtils.Glyph.SEARCH, 14, null));
        marketSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { renderCards(); }
            public void removeUpdate(DocumentEvent e) { renderCards(); }
            public void changedUpdate(DocumentEvent e) { renderCards(); }
        });
        category.addActionListener(e -> renderCards());
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(new EmptyBorder(10, 12, 8, 12));
        top.add(marketSearch, BorderLayout.CENTER);
        top.add(category, BorderLayout.EAST);

        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        JPanel wrap = new ScrollablePanel(new BorderLayout());
        wrap.add(cards, BorderLayout.NORTH);
        JScrollPane scroll = ScrollablePanel.verticalScroll(wrap);

        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private void renderCards() {
        cards.removeAll();
        String q = marketSearch.getText().trim().toLowerCase(Locale.ROOT);
        String cat = (String) category.getSelectedItem();
        for (PluginManifest m : manager.getCatalog()) {
            if (cat != null && !cat.startsWith("All") && !cat.equals(m.category())) continue;
            String hay = (m.displayName() + " " + m.description() + " " + m.author() + " " + String.join(" ", m.keywords()))
                    .toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            cards.add(card(m));
        }
        cards.revalidate();
        cards.repaint();
    }

    private JComponent card(PluginManifest m) {
        boolean installed = manager.isInstalled(m.id());
        JPanel card = new JPanel(new BorderLayout(12, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtils.surface(2));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(UIUtils.borderColor());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(10, 14, 10, 12));

        Color accent = switch (m.category()) {
            case PluginCatalog.CATEGORY_MCP -> UIUtils.ACCENT_COLOR;
            case PluginCatalog.CATEGORY_RULES -> UIUtils.ACCENT_PURPLE;
            default -> UIUtils.SUCCESS_COLOR;
        };
        JLabel icon = new JLabel(UIUtils.glyph(m.mcpServers().isEmpty() ? UIUtils.Glyph.MEMORY : UIUtils.Glyph.PLUGINS, 28, accent));
        icon.setVerticalAlignment(SwingConstants.TOP);
        card.add(icon, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(m.displayName());
        title.setFont(UIUtils.uiFont(Font.BOLD, 14f));
        JLabel meta = new JLabel((m.author().isBlank() ? "" : m.author() + " · ") + m.category() + " · " + m.contributionSummary());
        meta.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        meta.setForeground(UIUtils.accentText(accent));
        JLabel desc = new JLabel("<html>" + m.description() + "</html>");
        desc.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        for (JComponent c : new JComponent[]{title, meta, desc}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(c);
        }
        for (var s : m.mcpServers()) {
            JLabel launch = new JLabel("$ " + s.launchSummary());
            launch.setFont(UIUtils.getEditorFont(11));
            launch.setForeground(UIUtils.mutedForeground());
            launch.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(launch);
        }
        card.add(text, BorderLayout.CENTER);

        JButton action = installed ? new JButton("Installed ✓") : DialogSupport.primary("Install");
        action.setToolTipText(installed ? "Click to reinstall" : "Review and install");
        action.addActionListener(e -> installManifest(m));
        JPanel east = new JPanel(new BorderLayout());
        east.setOpaque(false);
        east.add(action, BorderLayout.NORTH);
        card.add(east, BorderLayout.EAST);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(new EmptyBorder(0, 12, 8, 12));
        wrap.add(card, BorderLayout.CENTER);
        return wrap;
    }

    // ------------------------------------------------------------------
    // Installed
    // ------------------------------------------------------------------

    private JComponent installedTab() {
        installedTable.setRowHeight(26);
        installedTable.setFillsViewportHeight(true);
        installedTable.setShowVerticalLines(false);
        installedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        installedTable.getColumnModel().getColumn(0).setMaxWidth(70);
        installedTable.getColumnModel().getColumn(5).setMaxWidth(110);
        installedTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showInstalledDetails();
        });

        JButton uninstall = new JButton("Uninstall");
        uninstall.addActionListener(e -> {
            InstalledPlugin p = installedModel.get(installedTable.getSelectedRow());
            if (p == null) return;
            int ok = JOptionPane.showConfirmDialog(this, "Uninstall " + p.manifest().displayName()
                    + "? Its memories, commands and MCP servers are removed.", "Uninstall Plugin", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
            try {
                manager.uninstall(p.id());
                Toast.success(this, "Uninstalled " + p.manifest().displayName());
            } catch (Exception ex) {
                Toast.error(this, "Uninstall failed: " + ex.getMessage());
            }
        });
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(installedTable), new JScrollPane(installedDetails));
        split.setResizeWeight(0.55);
        split.setBorder(null);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(10, 12, 0, 12));
        p.add(split, BorderLayout.CENTER);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.CENTER);
        wrap.add(DialogSupport.buttonBar(new JLabel("Tick 'Enabled' to toggle a plugin; 'MCP' starts or stops its servers."),
                uninstall), BorderLayout.SOUTH);
        return wrap;
    }

    private void showInstalledDetails() {
        InstalledPlugin p = installedModel.get(installedTable.getSelectedRow());
        if (p == null) {
            installedDetails.setText("");
            return;
        }
        PluginManifest m = p.manifest();
        StringBuilder sb = new StringBuilder();
        sb.append(m.displayName()).append("  v").append(m.version()).append("   (").append(p.format()).append(")\n");
        if (!m.description().isBlank()) sb.append(m.description()).append('\n');
        sb.append("\nInstalled: ").append(p.installedAt()).append("\nSource:    ").append(p.source())
                .append("\nLocation:  ").append(p.installPath()).append('\n');
        if (!m.mcpServers().isEmpty()) {
            sb.append("\nMCP servers ").append(p.mcpEnabled() ? "(running when enabled)" : "(stopped)").append(":\n");
            for (int i = 0; i < m.mcpServers().size(); i++) {
                String name = i < p.mcpServerNames().size() ? p.mcpServerNames().get(i) : m.mcpServers().get(i).name();
                sb.append("  • ").append(name).append(": ").append(m.mcpServers().get(i).launchSummary()).append('\n');
            }
        }
        if (!m.memories().isEmpty()) {
            sb.append("\nMemories:\n");
            m.memories().forEach(mc -> sb.append("  • ").append(mc.title()).append('\n'));
        }
        if (!m.commands().isEmpty()) {
            sb.append("\nCommands:\n");
            m.commands().forEach(c -> sb.append("  /").append(c.name()).append(c.description() != null ? " — " + c.description() : "").append('\n'));
        }
        installedDetails.setText(sb.toString());
        installedDetails.setCaretPosition(0);
    }

    private class InstalledTableModel extends AbstractTableModel {
        private final String[] cols = {"Enabled", "Plugin", "Version", "Format", "Contributes", "MCP"};
        private List<InstalledPlugin> rows = new ArrayList<>();

        void setRows(List<InstalledPlugin> r) {
            rows = r;
            fireTableDataChanged();
        }

        InstalledPlugin get(int i) {
            return i >= 0 && i < rows.size() ? rows.get(i) : null;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == 0 || c == 5 ? Boolean.class : String.class;
        }

        public boolean isCellEditable(int r, int c) {
            return c == 0 || (c == 5 && !rows.get(r).mcpServerNames().isEmpty());
        }

        public Object getValueAt(int r, int c) {
            InstalledPlugin p = rows.get(r);
            return switch (c) {
                case 0 -> p.enabled();
                case 1 -> p.manifest().displayName();
                case 2 -> p.manifest().version();
                case 3 -> p.format();
                case 4 -> p.manifest().contributionSummary();
                default -> !p.mcpServerNames().isEmpty() && p.mcpEnabled();
            };
        }

        public void setValueAt(Object v, int r, int c) {
            InstalledPlugin p = rows.get(r);
            if (c == 0) manager.setEnabled(p.id(), Boolean.TRUE.equals(v));
            if (c == 5) manager.setMcpServersEnabled(p.id(), Boolean.TRUE.equals(v));
        }
    }

    // ------------------------------------------------------------------
    // Install from folder / ZIP / URL
    // ------------------------------------------------------------------

    private JComponent installFromTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(16, 18, 16, 18));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 14, 0);

        JButton folder = new JButton("Choose Folder…");
        folder.addActionListener(e -> {
            JFileChooser ch = new JFileChooser();
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) previewAndInstall(ch.getSelectedFile().toPath());
        });
        p.add(option("From a folder", "A folder containing axiomate-plugin.json, or a Claude Code plugin with "
                + ".claude-plugin/plugin.json.", folder), c);

        JButton zip = new JButton("Choose ZIP…");
        zip.addActionListener(e -> {
            JFileChooser ch = new JFileChooser();
            ch.setFileFilter(new FileNameExtensionFilter("Plugin archives (*.zip)", "zip"));
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) previewAndInstall(ch.getSelectedFile().toPath());
        });
        c.gridy++;
        p.add(option("From a ZIP archive", "A .zip of a plugin folder (a single top-level folder is unwrapped automatically).", zip), c);

        JTextField url = new JTextField();
        url.putClientProperty("JTextField.placeholderText", "https://github.com/owner/repo/tree/main/plugins/my-plugin  or  https://…/plugin.zip");
        JButton download = new JButton("Download & Review");
        download.addActionListener(e -> downloadAndInstall(url.getText(), download));
        JPanel urlRow = new JPanel(new BorderLayout(8, 0));
        urlRow.add(url, BorderLayout.CENTER);
        urlRow.add(download, BorderLayout.EAST);
        c.gridy++;
        p.add(option("From a URL", "A GitHub repository or folder URL, or a direct HTTPS link to a .zip.", urlRow), c);

        c.gridy++;
        c.weighty = 1;
        p.add(new JLabel(), c);
        return p;
    }

    private JComponent option(String title, String description, JComponent control) {
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIUtils.borderColor()),
                new EmptyBorder(10, 12, 12, 12)));
        JLabel t = new JLabel(title);
        t.setFont(UIUtils.uiFont(Font.BOLD, 13f));
        JLabel d = new JLabel("<html>" + description + "</html>");
        d.setForeground(UIUtils.mutedForeground());
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.add(t);
        text.add(d);
        box.add(text, BorderLayout.NORTH);
        JPanel ctl = new JPanel(new BorderLayout());
        ctl.add(control, control instanceof JButton ? BorderLayout.WEST : BorderLayout.CENTER);
        box.add(ctl, BorderLayout.CENTER);
        return box;
    }

    private void previewAndInstall(Path path) {
        try {
            PluginPackage pkg = manager.readPackage(path);
            InstallOptions opts = confirm(pkg.manifest(), pkg.format() + " plugin from " + path.getFileName(), pkg.warnings());
            if (opts == null) return;
            InstalledPlugin p = manager.installPackage(pkg, path.toAbsolutePath().toString(), opts);
            Toast.success(this, "Installed " + p.manifest().displayName() + " — " + p.manifest().contributionSummary());
        } catch (Exception ex) {
            Toast.error(this, "Could not install plugin: " + ex.getMessage());
        }
    }

    private void downloadAndInstall(String url, JButton button) {
        if (url == null || url.isBlank()) return;
        button.setEnabled(false);
        button.setText("Downloading…");
        new SwingWorker<PluginPackage, Void>() {
            @Override
            protected PluginPackage doInBackground() throws Exception {
                return manager.downloadPackage(url);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                button.setText("Download & Review");
                try {
                    PluginPackage pkg = get();
                    InstallOptions opts = confirm(pkg.manifest(), pkg.format() + " plugin from " + url, pkg.warnings());
                    if (opts == null) return;
                    InstalledPlugin p = manager.installPackage(pkg, url.trim(), opts);
                    Toast.success(PluginManagerDialog.this, "Installed " + p.manifest().displayName());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    Toast.error(PluginManagerDialog.this, "Could not install from URL: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private InstallOptions confirm(PluginManifest m, String sourceLabel, List<String> warnings) {
        return new PluginInstallDialog(this, m, sourceLabel, warnings, manager.isInstalled(m.id()), projectRoot).showDialog();
    }

    private void installManifest(PluginManifest m) {
        InstallOptions opts = confirm(m, "Built-in marketplace", List.of());
        if (opts == null) return;
        try {
            manager.install(m, opts);
            Toast.success(this, "Installed " + m.displayName() + " — " + m.contributionSummary());
        } catch (Exception ex) {
            Toast.error(this, "Install failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private JComponent commandsTab() {
        JTable t = new JTable(commandsModel);
        t.setRowHeight(24);
        t.setFillsViewportHeight(true);
        t.setShowVerticalLines(false);
        t.getColumnModel().getColumn(0).setPreferredWidth(170);
        t.getColumnModel().getColumn(1).setPreferredWidth(520);
        t.getColumnModel().getColumn(2).setPreferredWidth(170);
        JButton reload = new JButton("Reload commands from coding agents");
        reload.setToolTipText("Re-read .claude/commands, ~/.codex/prompts, .agents/workflows, .gemini/commands…");
        reload.addActionListener(e -> {
            actions.reloadAgentCommands();
            commandsModel.setRows(SlashCommandRegistry.getInstance().all());
        });
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(10, 12, 0, 12));
        p.add(new JLabel("Type these in the agent chat, e.g. /review src/Main.java"), BorderLayout.NORTH);
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.CENTER);
        wrap.add(DialogSupport.buttonBar(null, reload), BorderLayout.SOUTH);
        return wrap;
    }

    private static class CommandsTableModel extends AbstractTableModel {
        private final String[] cols = {"Command", "Description", "Source"};
        private List<SlashCommand> rows = new ArrayList<>();

        void setRows(List<SlashCommand> r) {
            rows = r;
            fireTableDataChanged();
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Object getValueAt(int r, int c) {
            SlashCommand s = rows.get(r);
            return switch (c) {
                case 0 -> "/" + s.name();
                case 1 -> s.description();
                default -> s.source();
            };
        }
    }

    @Override
    public void dispose() {
        manager.removeChangeListener(changeListener);
        super.dispose();
    }
}

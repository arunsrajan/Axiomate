package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.plugins.PluginManager.InstallOptions;
import com.github.axiomate.agentic.ide.plugins.PluginManifest;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.CommandContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.McpServerContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.MemoryContribution;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review step before installing a plugin: shows what it adds (MCP servers with their exact launch command,
 * memories, commands), collects required environment variables, and asks whether MCP servers may start.
 */
public class PluginInstallDialog extends JDialog {

    private final Map<String, JPasswordField> envFields = new LinkedHashMap<>();
    private final JCheckBox startServers = new JCheckBox("Start the plugin's MCP servers now");
    private InstallOptions result;

    public PluginInstallDialog(Window owner, PluginManifest m, String sourceLabel, List<String> warnings,
                               boolean alreadyInstalled, Path projectRoot) {
        super(owner, "Install Plugin", ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout());
        add(DialogSupport.banner((alreadyInstalled ? "Reinstall " : "Install ") + m.displayName() + "  v" + m.version(),
                (m.author().isBlank() ? "" : "by " + m.author() + " · ") + m.category() + " · " + sourceLabel), BorderLayout.NORTH);

        JPanel body = new ScrollablePanel(null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(10, 16, 10, 16));

        if (!m.description().isBlank()) addText(body, m.description(), false);
        for (String w : warnings) {
            JTextArea l = wrappingText("⚠  " + w);
            l.setForeground(UIUtils.accentText(UIUtils.WARNING_COLOR));
            body.add(l);
        }

        if (!m.mcpServers().isEmpty()) {
            section(body, "MCP servers — these run as processes on your machine");
            for (McpServerContribution s : m.mcpServers()) {
                addText(body, "• " + (s.name() != null ? s.name() : m.id()) + (s.description() != null ? " — " + s.description() : ""), false);
                JTextField launch = new JTextField(s.launchSummary());
                launch.setEditable(false);
                launch.setFont(UIUtils.getEditorFont(12));
                launch.setAlignmentX(Component.LEFT_ALIGNMENT);
                launch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                body.add(launch);
                for (String env : s.requiredEnv()) {
                    JPanel row = new JPanel(new BorderLayout(8, 0));
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);
                    row.setBorder(new EmptyBorder(4, 12, 0, 0));
                    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                    JLabel label = new JLabel(env + ":");
                    label.setFont(UIUtils.getEditorFont(12));
                    JPasswordField field = new JPasswordField();
                    field.putClientProperty("JTextField.placeholderText", "leave blank to use the value from your environment");
                    envFields.put(env, field);
                    row.add(label, BorderLayout.WEST);
                    row.add(field, BorderLayout.CENTER);
                    body.add(row);
                }
            }
            startServers.setAlignmentX(Component.LEFT_ALIGNMENT);
            startServers.setToolTipText("Otherwise the servers are registered disabled; enable them later from Plugins or MCP settings");
            body.add(Box.createVerticalStrut(6));
            body.add(startServers);
        }
        if (!m.memories().isEmpty()) {
            section(body, "Agent memories (" + m.memories().size() + ")");
            for (MemoryContribution mc : m.memories()) {
                addText(body, "• " + mc.title() + "  [" + (mc.type() != null ? mc.type() : "LONG_TERM") + "]", false);
            }
        }
        if (!m.commands().isEmpty()) {
            section(body, "Slash commands (" + m.commands().size() + ")");
            for (CommandContribution c : m.commands()) {
                addText(body, "/" + c.name() + (c.description() != null ? "  —  " + c.description() : ""), true);
            }
        }

        add(ScrollablePanel.verticalScroll(body), BorderLayout.CENTER);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton install = DialogSupport.primary(alreadyInstalled ? "Reinstall" : "Install");
        install.addActionListener(e -> {
            Map<String, String> env = new LinkedHashMap<>();
            envFields.forEach((k, f) -> env.put(k, new String(f.getPassword())));
            result = new InstallOptions(startServers.isSelected(), env, projectRoot);
            dispose();
        });
        add(DialogSupport.buttonBar(null, cancel, install), BorderLayout.SOUTH);
        getRootPane().setDefaultButton(install);
        DialogSupport.closeOnEscape(this);
        setSize(640, Math.min(640, 260 + 26 * (m.memories().size() + m.commands().size()) + 90 * m.mcpServers().size()));
        setLocationRelativeTo(owner);
    }

    private static void section(JPanel body, String title) {
        body.add(Box.createVerticalStrut(10));
        JLabel l = UIUtils.sectionHeader(title);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(l);
        body.add(Box.createVerticalStrut(4));
    }

    private static void addText(JPanel body, String text, boolean mono) {
        JTextArea l = wrappingText(text);
        if (mono) l.setFont(UIUtils.getEditorFont(12));
        body.add(l);
    }

    /** Read-only, label-styled text that wraps to the dialog width. */
    private static JTextArea wrappingText(String text) {
        JTextArea a = new JTextArea(text);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setFont(UIUtils.uiFont(Font.PLAIN, 12.5f));
        a.setForeground(UIUtils.foreground());
        a.setBorder(new EmptyBorder(1, 0, 1, 0));
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        return a;
    }

    /**
     * Shows the dialog and returns the chosen options, or null when cancelled.
     */
    public InstallOptions showDialog() {
        setVisible(true);
        return result;
    }
}

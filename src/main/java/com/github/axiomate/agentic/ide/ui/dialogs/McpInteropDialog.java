package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.interop.CodingAgent;
import com.github.axiomate.agentic.ide.interop.McpConfigInterop;
import com.github.axiomate.agentic.ide.interop.McpConfigInterop.DiscoveredServer;
import com.github.axiomate.agentic.ide.mcp.McpManager;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.mcp.McpTransport;
import com.github.axiomate.agentic.ide.ui.util.Toast;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/**
 * Imports MCP server definitions from other coding agents' configs into Axiomate, and exports Axiomate's
 * MCP servers into another agent's config file.
 */
public class McpInteropDialog extends JDialog {

    private final Path projectDir;
    private final McpConfigInterop interop = new McpConfigInterop();

    public McpInteropDialog(Window owner, Path projectDir, boolean exportTab) {
        super(owner, "MCP Servers ⇄ Coding Agents", ModalityType.APPLICATION_MODAL);
        this.projectDir = projectDir;
        setSize(940, 580);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
        add(DialogSupport.banner("Share MCP servers between coding agents",
                "Reuse the MCP servers you configured in Claude Code, Codex, Cursor, Antigravity, Gemini CLI, Windsurf, "
                        + "VS Code/Copilot, Roo Code or Kiro — or publish Axiomate's servers to them."), BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Import into Axiomate", importTab());
        tabs.addTab("Export from Axiomate", exportTab());
        tabs.setSelectedIndex(exportTab ? 1 : 0);
        add(tabs, BorderLayout.CENTER);
        DialogSupport.closeOnEscape(this);
    }

    private static String launch(McpServerConfig c) {
        return c.getTransport() == McpTransport.SSE ? c.getUrl()
                : (c.getCommand() + " " + String.join(" ", c.getArgs())).trim();
    }

    private boolean existsInAxiomate(String name) {
        return McpManager.getInstance().getServerConfigs().stream().anyMatch(s -> s.getName().equalsIgnoreCase(name));
    }

    private JComponent importTab() {
        CheckTableModel<DiscoveredServer> model = new CheckTableModel<>(
                new String[]{"Server", "From", "Transport", "Command / URL", "Status"}, (d, c) -> switch (c) {
            case 0 -> d.config().getName();
            case 1 -> d.agent().getDisplayName();
            case 2 -> d.config().getTransport();
            case 3 -> launch(d.config());
            default -> (existsInAxiomate(d.config().getName()) ? "Already in Axiomate" : "New")
                    + (d.note() != null ? " · " + d.note() : "");
        });
        JTable table = DialogSupport.table(model, 32, 140, 120, 80, 360, 200);
        JCheckBox enable = new JCheckBox("Start imported servers now (otherwise they are added disabled)", false);
        JCheckBox overwrite = new JCheckBox("Overwrite servers that already exist in Axiomate", false);
        JLabel status = new JLabel(" ");
        JButton importBtn = DialogSupport.primary("Import Selected");

        Runnable reload = () -> {
            List<DiscoveredServer> found = interop.discover(projectDir);
            model.setRows(found, d -> !existsInAxiomate(d.config().getName()));
            status.setText(found.size() + " server(s) found in other agents' configs");
        };
        importBtn.addActionListener(e -> {
            int added = 0;
            int skipped = 0;
            for (DiscoveredServer d : model.getChecked()) {
                if (existsInAxiomate(d.config().getName()) && !overwrite.isSelected()) {
                    skipped++;
                    continue;
                }
                McpServerConfig cfg = d.config();
                cfg.setEnabled(enable.isSelected());
                McpManager.getInstance().addServer(cfg);
                added++;
            }
            Toast.success(this, "Imported " + added + " MCP server(s)" + (skipped > 0 ? ", skipped " + skipped + " existing" : ""));
            reload.run();
        });
        JButton rescan = new JButton("Rescan");
        rescan.addActionListener(e -> reload.run());

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(10, 12, 0, 12));
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel opts = new JPanel(new GridLayout(2, 1));
        opts.add(enable);
        opts.add(overwrite);
        p.add(opts, BorderLayout.SOUTH);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.CENTER);
        wrap.add(DialogSupport.buttonBar(status, rescan, importBtn), BorderLayout.SOUTH);
        reload.run();
        return wrap;
    }

    private JComponent exportTab() {
        CheckTableModel<McpServerConfig> model = new CheckTableModel<>(new String[]{"Server", "Transport", "Command / URL"},
                (s, c) -> switch (c) {
                    case 0 -> s.getName();
                    case 1 -> s.getTransport();
                    default -> launch(s);
                });
        model.setRows(McpManager.getInstance().getServerConfigs(), s -> s.isEnabled());
        JTable table = DialogSupport.table(model, 32, 160, 90, 520);

        JComboBox<CodingAgent> target = new JComboBox<>();
        for (CodingAgent a : CodingAgent.values()) {
            if (McpConfigInterop.exportTarget(a).isPresent()) target.addItem(a);
        }
        JLabel file = new JLabel();
        Runnable updateFile = () -> {
            CodingAgent a = (CodingAgent) target.getSelectedItem();
            McpConfigInterop.exportTarget(a).ifPresent(t -> file.setText("→ " + (t.scope().name().equals("PROJECT")
                    ? "<project>/" : "~/") + t.path()));
        };
        target.addActionListener(e -> updateFile.run());
        updateFile.run();
        JCheckBox overwrite = new JCheckBox("Overwrite servers with the same name", false);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(new JLabel("Target agent:"));
        top.add(target);
        top.add(file);
        top.add(overwrite);

        JButton exportBtn = DialogSupport.primary("Export Selected");
        JLabel status = new JLabel(" ");
        exportBtn.addActionListener(e -> {
            CodingAgent a = (CodingAgent) target.getSelectedItem();
            List<McpServerConfig> servers = model.getChecked();
            if (a == null || servers.isEmpty()) return;
            try {
                var res = interop.export(a, projectDir, servers, overwrite.isSelected());
                String msg = "Wrote " + res.written().size() + " server(s) to " + res.file();
                if (!res.skipped().isEmpty()) msg += "\nSkipped existing: " + String.join(", ", res.skipped());
                Toast.success(this, msg);
                status.setText("Last export: " + res.file().getFileName());
            } catch (Exception ex) {
                Toast.error(this, "Export failed: " + ex.getMessage());
            }
        });

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new EmptyBorder(10, 12, 0, 12));
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.CENTER);
        wrap.add(DialogSupport.buttonBar(status, exportBtn), BorderLayout.SOUTH);
        return wrap;
    }
}

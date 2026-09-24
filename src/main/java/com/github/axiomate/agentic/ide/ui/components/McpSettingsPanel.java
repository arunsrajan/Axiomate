package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.mcp.McpClient;
import com.github.axiomate.agentic.ide.mcp.McpManager;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.mcp.McpTransport;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings UI panel for configuring, testing, and managing Model Context Protocol (MCP) servers.
 */
public class McpSettingsPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(McpSettingsPanel.class);

    private final DefaultListModel<McpServerConfig> serverListModel = new DefaultListModel<>();
    private final JList<McpServerConfig> serverList = new JList<>(serverListModel);

    private final JTextField nameField = new JTextField();
    private final JComboBox<McpTransport> transportCombo = new JComboBox<>(McpTransport.values());
    private final JTextField commandField = new JTextField();
    private final JTextField argsField = new JTextField();
    private final JTextField urlField = new JTextField();
    private final JCheckBox enabledCheck = new JCheckBox("Enabled", true);
    private final JTextArea discoveredToolsArea = new JTextArea(6, 20);

    public McpSettingsPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 10, 8, 10));

        // 1. Top Bar: Add, Delete, Test, Reconnect
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addBtn = UIUtils.createPillButton("+ Add MCP Server", null, UIUtils.ACCENT_COLOR, Color.WHITE);
        addBtn.addActionListener(e -> newServerForm());

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteSelectedServer());

        JButton testBtn = new JButton("⚡ Test Connection");
        testBtn.addActionListener(e -> testSelectedServer());

        JButton reconnectBtn = new JButton("↻ Reconnect All");
        reconnectBtn.addActionListener(e -> McpManager.getInstance().connectAllEnabled());

        topBar.add(addBtn);
        topBar.add(deleteBtn);
        topBar.add(testBtn);
        topBar.add(reconnectBtn);
        add(topBar, BorderLayout.NORTH);

        // 2. Center: Split with Server List on Left, Config Editor on Right
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setCellRenderer(new McpServerListCellRenderer());
        serverList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                populateForm(serverList.getSelectedValue());
            }
        });

        JScrollPane listScroll = new JScrollPane(serverList);
        listScroll.setPreferredSize(new Dimension(240, 300));
        listScroll.setBorder(new CompoundBorder(new TitledBorder("Configured MCP Servers"), new EmptyBorder(4, 4, 4, 4)));

        // Form Panel
        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.setBorder(new CompoundBorder(new TitledBorder("Server Details & Protocol Settings"), new EmptyBorder(6, 8, 6, 8)));

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.25;
        grid.add(new JLabel("Server Name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.75;
        grid.add(nameField, gbc);

        // Transport
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.25;
        grid.add(new JLabel("Transport:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.75;
        grid.add(transportCombo, gbc);

        // Command (for Stdio)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.25;
        grid.add(new JLabel("Command:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.75;
        grid.add(commandField, gbc);

        // Args (for Stdio)
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.25;
        grid.add(new JLabel("Arguments:"), gbc);
        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 0.75;
        argsField.putClientProperty("JTextField.placeholderText", "Space or comma-separated args (e.g. -y @modelcontextprotocol/server-everything)");
        grid.add(argsField, gbc);

        // URL (for SSE)
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.25;
        grid.add(new JLabel("Endpoint URL (SSE):"), gbc);
        gbc.gridx = 1; gbc.gridy = 4; gbc.weightx = 0.75;
        urlField.putClientProperty("JTextField.placeholderText", "e.g. http://localhost:8000/sse");
        grid.add(urlField, gbc);

        // Status checkbox
        gbc.gridx = 1; gbc.gridy = 5; gbc.weightx = 0.75;
        grid.add(enabledCheck, gbc);

        formPanel.add(grid);
        formPanel.add(Box.createVerticalStrut(6));

        // Discovered Tools preview
        JPanel toolsPreviewPanel = new JPanel(new BorderLayout());
        toolsPreviewPanel.setBorder(new TitledBorder("Discovered Tools"));
        discoveredToolsArea.setEditable(false);
        discoveredToolsArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        discoveredToolsArea.setMargin(new Insets(4, 6, 4, 6));
        toolsPreviewPanel.add(new JScrollPane(discoveredToolsArea), BorderLayout.CENTER);
        formPanel.add(toolsPreviewPanel);

        // Save button
        JPanel formActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        JButton saveBtn = UIUtils.createPillButton("Save Server Config", null, UIUtils.ACCENT_COLOR, Color.WHITE);
        saveBtn.addActionListener(e -> saveCurrentServer());
        formActions.add(saveBtn);
        formPanel.add(formActions);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, new JScrollPane(formPanel));
        split.setResizeWeight(0.32);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        // Refresh on manager updates
        McpManager.getInstance().addChangeListener(this::refreshServerList);
        refreshServerList();
    }

    private void refreshServerList() {
        SwingUtilities.invokeLater(() -> {
            serverListModel.clear();
            List<McpServerConfig> configs = McpManager.getInstance().getServerConfigs();
            for (McpServerConfig cfg : configs) {
                serverListModel.addElement(cfg);
            }
            if (!serverListModel.isEmpty() && serverList.getSelectedIndex() < 0) {
                serverList.setSelectedIndex(0);
            }
        });
    }

    private void populateForm(McpServerConfig cfg) {
        if (cfg == null) {
            nameField.setText("");
            commandField.setText("");
            argsField.setText("");
            urlField.setText("");
            discoveredToolsArea.setText("");
            return;
        }

        nameField.setText(cfg.getName());
        transportCombo.setSelectedItem(cfg.getTransport());
        commandField.setText(cfg.getCommand());
        argsField.setText(String.join(" ", cfg.getArgs()));
        urlField.setText(cfg.getUrl());
        enabledCheck.setSelected(cfg.isEnabled());

        var tools = McpManager.getInstance().getDiscoveredTools(cfg.getName());
        if (tools.isEmpty()) {
            discoveredToolsArea.setText("No active tools discovered yet. Click 'Test Connection' or enable server.");
        } else {
            StringBuilder sb = new StringBuilder("Discovered " + tools.size() + " MCP tools:\n");
            for (var t : tools) {
                sb.append("• ").append(t.getName()).append(": ").append(t.getDescription()).append("\n\n");
            }
            discoveredToolsArea.setText(sb.toString());
        }
    }

    private void newServerForm() {
        nameField.setText("new_mcp_server");
        transportCombo.setSelectedItem(McpTransport.STDIO);
        commandField.setText("npx");
        argsField.setText("-y @modelcontextprotocol/server-everything");
        urlField.setText("");
        enabledCheck.setSelected(true);
        discoveredToolsArea.setText("New server. Save and click 'Test Connection' to discover tools.");
        nameField.requestFocus();
    }

    private void saveCurrentServer() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a valid server name.", "Validation Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        McpServerConfig cfg = new McpServerConfig();
        cfg.setName(name);
        cfg.setTransport((McpTransport) transportCombo.getSelectedItem());
        cfg.setCommand(commandField.getText().trim());

        String argsStr = argsField.getText().trim();
        if (!argsStr.isEmpty()) {
            cfg.setArgs(Arrays.asList(argsStr.split("\\s+")));
        }

        cfg.setUrl(urlField.getText().trim());
        cfg.setEnabled(enabledCheck.isSelected());

        McpManager.getInstance().addServer(cfg);
        JOptionPane.showMessageDialog(this, "Saved MCP server: " + name, "Server Saved", JOptionPane.INFORMATION_MESSAGE);
        refreshServerList();
    }

    private void deleteSelectedServer() {
        McpServerConfig selected = serverList.getSelectedValue();
        if (selected == null) return;

        int conf = JOptionPane.showConfirmDialog(this, "Delete MCP server '" + selected.getName() + "'?", "Delete MCP Server", JOptionPane.YES_NO_OPTION);
        if (conf == JOptionPane.YES_OPTION) {
            McpManager.getInstance().removeServer(selected.getName());
            refreshServerList();
        }
    }

    private void testSelectedServer() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify a server to test.", "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        McpServerConfig cfg = new McpServerConfig();
        cfg.setName(name);
        cfg.setTransport((McpTransport) transportCombo.getSelectedItem());
        cfg.setCommand(commandField.getText().trim());
        String argsStr = argsField.getText().trim();
        if (!argsStr.isEmpty()) {
            cfg.setArgs(Arrays.asList(argsStr.split("\\s+")));
        }
        cfg.setUrl(urlField.getText().trim());

        discoveredToolsArea.setText("Testing connection and sending JSON-RPC initialize handshake...");

        McpManager.getInstance().testConnection(cfg).whenComplete((tools, ex) -> {
            SwingUtilities.invokeLater(() -> {
                if (ex != null) {
                    discoveredToolsArea.setText("❌ Connection Failed:\n" + ex.getMessage());
                    JOptionPane.showMessageDialog(this, "MCP Server Test Failed:\n" + ex.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
                } else {
                    StringBuilder sb = new StringBuilder("✅ Connected successfully! Discovered " + tools.size() + " tool(s):\n\n");
                    for (McpClient.McpToolDefinition t : tools) {
                        sb.append("• Tool: ").append(t.name()).append("\n")
                          .append("  Description: ").append(t.description()).append("\n")
                          .append("  Schema: ").append(t.inputSchema()).append("\n\n");
                    }
                    discoveredToolsArea.setText(sb.toString());
                    JOptionPane.showMessageDialog(this, "Successfully connected! Discovered " + tools.size() + " tool(s).", "Test Succeeded", JOptionPane.INFORMATION_MESSAGE);
                }
            });
        });
    }

    private static class McpServerListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof McpServerConfig cfg) {
                boolean connected = McpManager.getInstance().isConnected(cfg.getName());
                String status = !cfg.isEnabled() ? "⚪ [Disabled] " : (connected ? "🟢 [Connected] " : "🟡 [Configured] ");
                setText(status + cfg.getName() + " (" + cfg.getTransport() + ")");
                setIcon(UIUtils.createGearIcon(12, connected ? UIUtils.SUCCESS_COLOR : Color.GRAY));
            }
            return this;
        }
    }
}


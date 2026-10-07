package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.interop.AgentCommandInterop;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop;
import com.github.axiomate.agentic.ide.interop.CodingAgent;
import com.github.axiomate.agentic.ide.interop.ExternalSessionImporter;
import com.github.axiomate.agentic.ide.interop.McpConfigInterop;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Sidebar view summarising what other coding agents (Claude Code, Codex, Cursor, Antigravity, ...)
 * have stored for the current project, with one-click import and export of memory, MCP servers,
 * commands and sessions.
 */
public class AgentSyncPanel extends JPanel {

    /** Per-agent counts found on disk. */
    record AgentStatus(int memoryFiles, int commands, int mcpServers, int sessions) {
        int total() {
            return memoryFiles + commands + mcpServers + sessions;
        }
    }

    private final JPanel agentsList = new JPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final IdeActions actions;
    private SwingWorker<Map<CodingAgent, AgentStatus>, Void> worker;

    public AgentSyncPanel(IdeActions actions) {
        super(new BorderLayout());
        this.actions = actions != null ? actions : IdeActions.NONE;

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(8, 10, 4, 6));
        header.add(UIUtils.sectionHeader("Agent Sync"), BorderLayout.CENTER);
        header.add(UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.REFRESH, 16, null), "Rescan", e -> rescan()), BorderLayout.EAST);

        JLabel intro = new JLabel("<html>Bring memory, rules, MCP servers, commands and sessions from other coding agents "
                + "into Axiomate — or push Axiomate's memory back to them.</html>");
        intro.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        intro.setBorder(new EmptyBorder(0, 10, 8, 10));

        JPanel actionsPanel = new JPanel(new GridLayout(0, 1, 0, 4));
        actionsPanel.setOpaque(false);
        actionsPanel.setBorder(new EmptyBorder(0, 10, 10, 10));
        actionsPanel.add(actionButton("Import memory & rules…", UIUtils.Glyph.DOWNLOAD, this.actions::openMemoryImport));
        actionsPanel.add(actionButton("Export memory to agents…", UIUtils.Glyph.UPLOAD, this.actions::openMemoryExport));
        actionsPanel.add(actionButton("Import / export MCP servers…", UIUtils.Glyph.PLUGINS, () -> this.actions.openMcpInterop(false)));
        actionsPanel.add(actionButton("Import sessions…", UIUtils.Glyph.SESSIONS, this.actions::openExternalSessionImport));
        actionsPanel.add(actionButton("Reload slash commands", UIUtils.Glyph.COMMAND, () -> {
            this.actions.reloadAgentCommands();
            rescan();
        }));

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{header, intro, actionsPanel}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            north.add(c);
        }
        JLabel detected = UIUtils.sectionHeader("Detected in this project");
        detected.setBorder(new EmptyBorder(4, 10, 4, 10));
        detected.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(detected);
        add(north, BorderLayout.NORTH);

        agentsList.setLayout(new BoxLayout(agentsList, BoxLayout.Y_AXIS));
        agentsList.setOpaque(false);
        JPanel listWrap = new ScrollablePanel(new BorderLayout());
        listWrap.setOpaque(false);
        listWrap.add(agentsList, BorderLayout.NORTH);
        JScrollPane scroll = ScrollablePanel.verticalScroll(listWrap);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        statusLabel.setBorder(new EmptyBorder(4, 10, 6, 10));
        statusLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        add(statusLabel, BorderLayout.SOUTH);

        ProjectManager.getInstance().addProjectChangeListener(dir -> SwingUtilities.invokeLater(this::rescan));
        UIUtils.addThemeListener(this::applyColors);
        applyColors();
        rescan();
    }

    private void applyColors() {
        setBackground(UIUtils.surface(1));
        statusLabel.setForeground(UIUtils.mutedForeground());
    }

    private JButton actionButton(String text, UIUtils.Glyph glyph, Runnable r) {
        JButton b = new JButton(text, UIUtils.glyph(glyph, 14, null));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    public void rescan() {
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        Path project = dir != null ? dir.toPath() : null;
        if (worker != null) worker.cancel(true);
        statusLabel.setText("Scanning…");
        worker = new SwingWorker<>() {
            @Override
            protected Map<CodingAgent, AgentStatus> doInBackground() {
                return scan(project);
            }

            @Override
            protected void done() {
                if (isCancelled()) return;
                try {
                    render(get());
                } catch (Exception e) {
                    statusLabel.setText("Scan failed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    static Map<CodingAgent, AgentStatus> scan(Path project) {
        Map<CodingAgent, int[]> counts = new EnumMap<>(CodingAgent.class);
        for (CodingAgent a : CodingAgent.values()) counts.put(a, new int[4]);
        new AgentMemoryInterop().detectAll(project).forEach(s -> counts.get(s.agent())[0]++);
        new AgentCommandInterop().discover(project).forEach(c -> CodingAgent.fromId(c.source().replace("agent:", ""))
                .ifPresent(a -> counts.get(a)[1]++));
        new McpConfigInterop().discover(project).forEach(d -> counts.get(d.agent())[2]++);
        new ExternalSessionImporter().findAll(project).forEach(r -> counts.get(r.agent())[3]++);
        Map<CodingAgent, AgentStatus> result = new EnumMap<>(CodingAgent.class);
        counts.forEach((a, c) -> result.put(a, new AgentStatus(c[0], c[1], c[2], c[3])));
        return result;
    }

    private void render(Map<CodingAgent, AgentStatus> status) {
        agentsList.removeAll();
        int found = 0;
        for (Map.Entry<CodingAgent, AgentStatus> e : status.entrySet()) {
            if (e.getValue().total() > 0) {
                agentsList.add(agentRow(e.getKey(), e.getValue()));
                found++;
            }
        }
        if (found == 0) {
            JLabel none = new JLabel("<html>No Claude Code, Codex, Cursor, Antigravity, Gemini, Windsurf, Copilot, Cline, Roo or Kiro "
                    + "files found for this project.</html>");
            none.setBorder(new EmptyBorder(6, 12, 6, 12));
            none.setForeground(UIUtils.mutedForeground());
            agentsList.add(none);
        }
        statusLabel.setText(found + " of " + CodingAgent.values().length + " agents have data here");
        agentsList.revalidate();
        agentsList.repaint();
    }

    private JComponent agentRow(CodingAgent agent, AgentStatus s) {
        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtils.surface(3));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(7, 10, 7, 10));
        JLabel name = new JLabel(agent.getDisplayName());
        name.setFont(UIUtils.uiFont(Font.BOLD, 12.5f));
        StringBuilder meta = new StringBuilder();
        append(meta, s.memoryFiles(), "file");
        append(meta, s.commands(), "cmd");
        append(meta, s.mcpServers(), "MCP");
        append(meta, s.sessions(), "session");
        JLabel detail = new JLabel(meta.toString());
        detail.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        detail.setForeground(UIUtils.mutedForeground());
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(detail);
        row.add(text, BorderLayout.CENTER);
        row.setToolTipText("<html><b>" + agent.getDisplayName() + "</b> (" + agent.getVendor() + ")<br>"
                + s.memoryFiles() + " memory/rule file(s), " + s.commands() + " command(s), "
                + s.mcpServers() + " MCP server(s), " + s.sessions() + " session(s)</html>");

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(0, 8, 6, 8));
        wrap.add(row, BorderLayout.CENTER);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        return wrap;
    }

    private static void append(StringBuilder sb, int n, String noun) {
        if (n <= 0) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(n).append(' ').append(noun).append(n == 1 || noun.equals("MCP") ? "" : "s");
    }
}

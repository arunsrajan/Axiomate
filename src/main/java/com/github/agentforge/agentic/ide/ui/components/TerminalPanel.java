package com.github.agentforge.agentic.ide.ui.components;

import com.github.agentforge.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Integrated bottom panel containing interactive terminal, agentic memory browser,
 * tool execution logs, and build output.
 */
public class TerminalPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(TerminalPanel.class);

    private final JTabbedPane tabbedPane;
    private final JTextArea terminalArea;
    private final JTextArea agentLogsArea;
    private final JTextArea buildArea;
    private final JTextField commandInput;
    private final MemoryPanel memoryPanel;

    public TerminalPanel() {
        setLayout(new BorderLayout());
        tabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // Tab 1: Terminal
        JPanel termTab = new JPanel(new BorderLayout());
        terminalArea = createConsoleArea();
        JScrollPane termScroll = new JScrollPane(terminalArea);
        termScroll.setBorder(null);

        JPanel termInputPanel = new JPanel(new BorderLayout(6, 0));
        termInputPanel.setBorder(new EmptyBorder(4, 6, 4, 6));

        JLabel promptLabel = new JLabel("$ ");
        promptLabel.setFont(new Font("Monospaced", Font.BOLD, 13));

        commandInput = new JTextField();
        commandInput.setFont(new Font("Monospaced", Font.PLAIN, 13));
        commandInput.addActionListener(e -> executeTerminalInput());

        JButton runBtn = new JButton("Run");
        runBtn.addActionListener(e -> executeTerminalInput());

        JButton clearTermBtn = new JButton("Clear");
        clearTermBtn.addActionListener(e -> terminalArea.setText(""));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnPanel.add(runBtn);
        btnPanel.add(clearTermBtn);

        termInputPanel.add(promptLabel, BorderLayout.WEST);
        termInputPanel.add(commandInput, BorderLayout.CENTER);
        termInputPanel.add(btnPanel, BorderLayout.EAST);

        termTab.add(termScroll, BorderLayout.CENTER);
        termTab.add(termInputPanel, BorderLayout.SOUTH);

        // Tab 2: Agentic AI Memory
        memoryPanel = new MemoryPanel();

        // Tab 3: Agent Tool Logs
        JPanel agentTab = new JPanel(new BorderLayout());
        agentLogsArea = createConsoleArea();
        JScrollPane agentScroll = new JScrollPane(agentLogsArea);
        agentScroll.setBorder(null);

        JPanel agentToolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton clearAgentBtn = new JButton("Clear Logs");
        clearAgentBtn.addActionListener(e -> agentLogsArea.setText(""));
        agentToolBar.add(clearAgentBtn);

        agentTab.add(agentScroll, BorderLayout.CENTER);
        agentTab.add(agentToolBar, BorderLayout.SOUTH);

        // Tab 4: Build & Run
        JPanel buildTab = new JPanel(new BorderLayout());
        buildArea = createConsoleArea();
        JScrollPane buildScroll = new JScrollPane(buildArea);
        buildScroll.setBorder(null);

        JPanel buildToolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton clearBuildBtn = new JButton("Clear Build");
        clearBuildBtn.addActionListener(e -> buildArea.setText(""));
        buildToolBar.add(clearBuildBtn);

        buildTab.add(buildScroll, BorderLayout.CENTER);
        buildTab.add(buildToolBar, BorderLayout.SOUTH);

        tabbedPane.addTab("Terminal", termTab);
        tabbedPane.addTab("Agentic AI Memory", memoryPanel);
        tabbedPane.addTab("Agent Logs & Tool Traces", agentTab);
        tabbedPane.addTab("Build & Run", buildTab);

        add(tabbedPane, BorderLayout.CENTER);

        appendTerminal("AgentForge AI Agent IDE Terminal ready. Current directory: " +
                ProjectManager.getInstance().getCurrentProjectDirectory().getAbsolutePath() + "\n");
    }

    private JTextArea createConsoleArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setBackground(new Color(24, 24, 24));
        area.setForeground(new Color(220, 220, 220));
        area.setCaretColor(Color.WHITE);
        area.setMargin(new Insets(6, 8, 6, 8));
        return area;
    }

    public MemoryPanel getMemoryPanel() {
        return memoryPanel;
    }

    public void selectMemoryTab() {
        tabbedPane.setSelectedIndex(1);
    }

    public void appendTerminal(String text) {
        SwingUtilities.invokeLater(() -> {
            terminalArea.append(text);
            terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
        });
    }

    public void appendAgentLog(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            agentLogsArea.append("[" + time + "] " + title + "\n" + message + "\n\n");
            agentLogsArea.setCaretPosition(agentLogsArea.getDocument().getLength());
        });
    }

    public void appendBuildOutput(String text) {
        SwingUtilities.invokeLater(() -> {
            buildArea.append(text);
            buildArea.setCaretPosition(buildArea.getDocument().getLength());
            tabbedPane.setSelectedIndex(3);
        });
    }

    private void executeTerminalInput() {
        String cmd = commandInput.getText().trim();
        if (cmd.isEmpty()) return;

        commandInput.setText("");
        appendTerminal("\n$ " + cmd + "\n");

        new Thread(() -> {
            try {
                File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = isWindows
                        ? new ProcessBuilder("cmd.exe", "/c", cmd)
                        : new ProcessBuilder("bash", "-c", cmd);
                pb.directory(dir);
                pb.redirectErrorStream(true);

                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendTerminal(line + "\n");
                    }
                }
                int exit = process.waitFor();
                appendTerminal("[Process finished with exit code " + exit + "]\n");
            } catch (Exception e) {
                appendTerminal("Error executing command: " + e.getMessage() + "\n");
            }
        }).start();
    }
}

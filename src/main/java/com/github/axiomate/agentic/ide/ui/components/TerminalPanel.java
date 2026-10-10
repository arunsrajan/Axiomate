package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.util.ProjectManager;
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
    private final SearchPanel searchPanel;
    /** Command running in the terminal tab, if any (Ctrl+C or Stop ends it). */
    private volatile Process runningProcess;
    /** Set from submit until the command ends, so a quick second Enter cannot start another one. */
    private final java.util.concurrent.atomic.AtomicBoolean commandActive = new java.util.concurrent.atomic.AtomicBoolean();
    /** Folder later commands run in; "cd" changes it, switching project resets it. */
    private volatile File terminalDir;
    /** Older output is trimmed so a chatty command cannot grow the console without limit. */
    static final int MAX_CONSOLE_CHARS = 500_000;

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
        commandInput.setToolTipText("Enter runs the command · Ctrl+C stops the running command");
        commandInput.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
                java.awt.event.InputEvent.CTRL_DOWN_MASK), "terminal-stop");
        commandInput.getActionMap().put("terminal-stop", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (runningProcess != null) stopRunningCommand();
                else commandInput.copy(); // nothing running: keep Ctrl+C as copy
            }
        });

        JButton runBtn = new JButton("Run");
        runBtn.addActionListener(e -> executeTerminalInput());
        JButton stopBtn = new JButton("Stop");
        stopBtn.setToolTipText("Stop the running command (Ctrl+C)");
        stopBtn.addActionListener(e -> stopRunningCommand());

        JButton clearTermBtn = new JButton("Clear");
        clearTermBtn.addActionListener(e -> terminalArea.setText(""));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnPanel.add(runBtn);
        btnPanel.add(stopBtn);
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
        searchPanel = new SearchPanel();
        tabbedPane.addTab("Search", searchPanel);

        add(tabbedPane, BorderLayout.CENTER);
        // A new project starts the terminal in its own folder, not where a "cd" in the previous one left it
        ProjectManager.getInstance().addProjectChangeListener(dir -> terminalDir = null);

        appendTerminal("Axiomate AI Agent IDE Terminal ready. Current directory: " +
                ProjectManager.getInstance().getCurrentProjectDirectory().getAbsolutePath() + "\n");
    }

    private JTextArea createConsoleArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(com.github.axiomate.agentic.ide.ui.util.UIUtils.getEditorFont(12));
        area.setMargin(new Insets(6, 8, 6, 8));
        styleConsole(area);
        com.github.axiomate.agentic.ide.ui.util.UIUtils.addThemeListener(() -> styleConsole(area));
        return area;
    }

    private static void styleConsole(JTextArea area) {
        area.setBackground(com.github.axiomate.agentic.ide.ui.util.UIUtils.consoleBackground());
        area.setForeground(com.github.axiomate.agentic.ide.ui.util.UIUtils.consoleForeground());
        area.setCaretColor(com.github.axiomate.agentic.ide.ui.util.UIUtils.consoleForeground());
    }

    public MemoryPanel getMemoryPanel() {
        return memoryPanel;
    }

    public void selectMemoryTab() {
        tabbedPane.setSelectedIndex(1);
    }

    public SearchPanel getSearchPanel() {
        return searchPanel;
    }

    /** Find in Files: shows the Search tab and searches {@code initialQuery} when one is given. */
    public void showSearch(String initialQuery) {
        tabbedPane.setSelectedComponent(searchPanel);
        searchPanel.focusSearch(initialQuery);
    }

    public void appendTerminal(String text) {
        SwingUtilities.invokeLater(() -> {
            terminalArea.append(text);
            int excess = terminalArea.getDocument().getLength() - MAX_CONSOLE_CHARS;
            if (excess > 0) terminalArea.replaceRange("", 0, excess);
            terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
        });
    }

    /** Stops the command running in the terminal, including the processes it started. */
    public void stopRunningCommand() {
        Process p = runningProcess;
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        appendTerminal("^C\n");
    }

    /** "cd dir" on its own changes the folder later commands run in (each command runs in a fresh shell). */
    static File resolveCd(String cmd, File current) {
        // "cd x && make" and friends run as one shell command; only a bare cd is remembered.
        if (cmd.matches(".*(&&|\\|\\||;|\\||&|>|<|\\$\\(|`).*")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^cd(?:\\s+(.+?))?\\s*$").matcher(cmd);
        if (!m.matches()) return null;
        String target = m.group(1) == null ? System.getProperty("user.home")
                : m.group(1).replaceFirst("(?i)^/d\\s+", "").replaceAll("^[\"']|[\"']$", "");
        if (target.equals("~")) target = System.getProperty("user.home");
        else if (target.startsWith("~/")) target = System.getProperty("user.home") + target.substring(1);
        java.nio.file.Path p = java.nio.file.Path.of(target);
        return (p.isAbsolute() ? p : current.toPath().resolve(p)).normalize().toFile();
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
        if (runCommand(cmd)) commandInput.setText("");
    }

    /**
     * Runs a command in the terminal tab (from the input line or a menu, e.g. Run → mvn test).
     *
     * @return false when another command is still running
     */
    public boolean runCommand(String cmd) {
        if (commandActive.get()) {
            appendTerminal("A command is still running. Press Ctrl+C or Stop to end it first.\n");
            return false;
        }
        SwingUtilities.invokeLater(() -> tabbedPane.setSelectedIndex(0));
        appendTerminal("\n$ " + cmd + "\n");

        File dir = currentTerminalDir();
        File cdTarget = resolveCd(cmd, dir);
        if (cdTarget != null) {
            if (cdTarget.isDirectory()) {
                terminalDir = cdTarget;
                appendTerminal(cdTarget.getAbsolutePath() + "\n");
            } else {
                appendTerminal("cd: no such directory: " + cdTarget + "\n");
            }
            return true;
        }

        if (!commandActive.compareAndSet(false, true)) {
            appendTerminal("A command is still running. Press Ctrl+C or Stop to end it first.\n");
            return false;
        }
        Thread runner = new Thread(() -> {
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = isWindows
                        ? new ProcessBuilder("cmd.exe", "/c", cmd)
                        : new ProcessBuilder("bash", "-c", cmd);
                pb.directory(dir);
                pb.redirectErrorStream(true);

                Process process = pb.start();
                runningProcess = process;
                process.getOutputStream().close(); // no interactive input: prompts fail instead of hanging forever
                java.nio.charset.Charset charset = isWindows ? nativeCharset() : StandardCharsets.UTF_8;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendTerminal(line + "\n");
                    }
                }
                int exit = process.waitFor();
                appendTerminal("[Process finished with exit code " + exit + "]\n");
            } catch (Exception e) {
                appendTerminal("Error executing command: " + e.getMessage() + "\n");
            } finally {
                runningProcess = null;
                commandActive.set(false);
            }
        }, "axiomate-terminal");
        runner.setDaemon(true); // never keeps the IDE from exiting
        runner.start();
        return true;
    }

    private File currentTerminalDir() {
        File dir = terminalDir;
        return dir != null && dir.isDirectory() ? dir : ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    private static java.nio.charset.Charset nativeCharset() {
        try {
            String enc = System.getProperty("native.encoding");
            return enc != null ? java.nio.charset.Charset.forName(enc) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}


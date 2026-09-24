package com.github.agentforge.agentic.ide.ui.menu;

import com.github.agentforge.agentic.ide.agent.memory.MemoryManager;
import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.ui.components.EditorPanel;
import com.github.agentforge.agentic.ide.ui.components.TerminalPanel;
import com.github.agentforge.agentic.ide.ui.util.UIUtils;
import com.github.agentforge.agentic.ide.util.ProjectManager;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.function.Consumer;

/**
 * Main application menu bar for AgentForge AI IDE with Agentic Memory integration.
 */
public class AppMenuBar extends JMenuBar {

    public AppMenuBar(JFrame mainFrame,
                      EditorPanel editorPanel,
                      TerminalPanel terminalPanel,
                      Consumer<String> agentPromptConsumer,
                      Runnable stopAgentRunnable,
                      Runnable clearAgentRunnable,
                      Runnable openSettingsRunnable,
                      Runnable openMcpSettingsRunnable,
                      Runnable toggleExplorerRunnable,
                      Runnable toggleAgentRunnable,
                      Runnable toggleTerminalRunnable,
                      Runnable runActiveFileRunnable) {

        // 1. FILE MENU
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem newFileItem = new JMenuItem("New File", KeyEvent.VK_N);
        newFileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        newFileItem.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(mainFrame, "Enter file name:", "New File", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.isBlank()) {
                editorPanel.newFile(name.trim(), "");
            }
        });

        JMenuItem openFileItem = new JMenuItem("Open File...", KeyEvent.VK_O);
        openFileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openFileItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(ProjectManager.getInstance().getCurrentProjectDirectory());
            if (chooser.showOpenDialog(mainFrame) == JFileChooser.APPROVE_OPTION) {
                editorPanel.openFile(chooser.getSelectedFile());
            }
        });

        JMenuItem openProjectItem = new JMenuItem("Open Project Folder...", KeyEvent.VK_P);
        openProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        openProjectItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(mainFrame) == JFileChooser.APPROVE_OPTION) {
                File dir = chooser.getSelectedFile();
                ProjectManager.getInstance().setCurrentProjectDirectory(dir);
            }
        });

        JMenuItem saveItem = new JMenuItem("Save", KeyEvent.VK_S);
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> editorPanel.saveActiveFile());

        JMenuItem saveAsItem = new JMenuItem("Save As...");
        saveAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveAsItem.addActionListener(e -> editorPanel.saveActiveFileAs());

        JMenuItem closeTabItem = new JMenuItem("Close Active Tab", KeyEvent.VK_W);
        closeTabItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        closeTabItem.addActionListener(e -> editorPanel.closeActiveTab());

        JMenuItem exitItem = new JMenuItem("Exit", KeyEvent.VK_X);
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newFileItem);
        fileMenu.add(openFileItem);
        fileMenu.add(openProjectItem);
        fileMenu.addSeparator();
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);
        fileMenu.add(closeTabItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        add(fileMenu);

        // 2. EDIT MENU
        JMenu editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);

        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> {
            var ed = editorPanel.getActiveEditor();
            if (ed != null && ed.canUndo()) ed.undoLastAction();
        });

        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        redoItem.addActionListener(e -> {
            var ed = editorPanel.getActiveEditor();
            if (ed != null && ed.canRedo()) ed.redoLastAction();
        });

        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK));
        cutItem.addActionListener(e -> {
            var ed = editorPanel.getActiveEditor();
            if (ed != null) ed.cut();
        });

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> {
            var ed = editorPanel.getActiveEditor();
            if (ed != null) ed.copy();
        });

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        pasteItem.addActionListener(e -> {
            var ed = editorPanel.getActiveEditor();
            if (ed != null) ed.paste();
        });

        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAllItem.addActionListener(e -> {
            var ed = editorPanel.getActiveEditor();
            if (ed != null) ed.selectAll();
        });

        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(cutItem);
        editMenu.add(copyItem);
        editMenu.add(pasteItem);
        editMenu.addSeparator();
        editMenu.add(selectAllItem);
        add(editMenu);

        // 3. AI AGENT MENU
        JMenu agentMenu = new JMenu("AI Agent");
        agentMenu.setMnemonic(KeyEvent.VK_A);

        JMenuItem explainItem = new JMenuItem("Explain Active Code", UIUtils.createSparkleIcon(14, UIUtils.ACCENT_PURPLE));
        explainItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        explainItem.addActionListener(e -> agentPromptConsumer.accept("Explain this code in detail and highlight key logic"));

        JMenuItem refactorItem = new JMenuItem("Refactor & Modernize Code", UIUtils.createSparkleIcon(14, UIUtils.ACCENT_COLOR));
        refactorItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        refactorItem.addActionListener(e -> agentPromptConsumer.accept("Refactor and modernize this code for clarity, robustness, and performance"));

        JMenuItem testGenItem = new JMenuItem("Generate Unit Tests", UIUtils.createSparkleIcon(14, UIUtils.SUCCESS_COLOR));
        testGenItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        testGenItem.addActionListener(e -> agentPromptConsumer.accept("Generate comprehensive JUnit 5 test cases covering all edge cases"));

        JMenuItem debugItem = new JMenuItem("Diagnose Bugs & Fixes", UIUtils.createSparkleIcon(14, UIUtils.WARNING_COLOR));
        debugItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        debugItem.addActionListener(e -> agentPromptConsumer.accept("Diagnose potential bugs, security vulnerabilities, or performance bottlenecks in this code"));

        JMenuItem stopAgentItem = new JMenuItem("Stop Current Agent Task", UIUtils.createStopIcon(14, UIUtils.ERROR_COLOR));
        stopAgentItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        stopAgentItem.addActionListener(e -> stopAgentRunnable.run());

        JMenuItem clearAgentItem = new JMenuItem("Clear Conversation");
        clearAgentItem.addActionListener(e -> clearAgentRunnable.run());

        // Memory Submenu
        JMenu memorySubMenu = new JMenu("Agentic AI Memory");
        memorySubMenu.setIcon(UIUtils.createSparkleIcon(14, UIUtils.ACCENT_PURPLE));

        JMenuItem importMemItem = new JMenuItem("Import All Memory into IDE...");
        importMemItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        importMemItem.addActionListener(e -> {
            terminalPanel.selectMemoryTab();
            terminalPanel.getMemoryPanel().importMemories();
        });

        JMenuItem exportMemItem = new JMenuItem("Export All Memories...");
        exportMemItem.addActionListener(e -> {
            terminalPanel.selectMemoryTab();
            terminalPanel.getMemoryPanel().exportMemories();
        });

        JMenuItem viewMemItem = new JMenuItem("View Memory Store");
        viewMemItem.addActionListener(e -> terminalPanel.selectMemoryTab());

        JMenuItem clearMemItem = new JMenuItem("Clear Memory Store");
        clearMemItem.addActionListener(e -> {
            int conf = JOptionPane.showConfirmDialog(mainFrame, "Are you sure you want to clear all agent memories?", "Clear Memory", JOptionPane.YES_NO_OPTION);
            if (conf == JOptionPane.YES_OPTION) {
                MemoryManager.getInstance().clear();
            }
        });

        memorySubMenu.add(importMemItem);
        memorySubMenu.add(exportMemItem);
        memorySubMenu.addSeparator();
        memorySubMenu.add(viewMemItem);
        memorySubMenu.add(clearMemItem);

        // MCP Submenu
        JMenu mcpSubMenu = new JMenu("Model Context Protocol (MCP)");
        mcpSubMenu.setIcon(UIUtils.createGearIcon(14, UIUtils.ACCENT_COLOR));

        JMenuItem configureMcpItem = new JMenuItem("Configure MCP Servers...");
        configureMcpItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        configureMcpItem.addActionListener(e -> openMcpSettingsRunnable.run());

        JMenuItem reconnectMcpItem = new JMenuItem("Reconnect All MCP Servers");
        reconnectMcpItem.addActionListener(e -> {
            com.github.agentforge.agentic.ide.mcp.McpManager.getInstance().connectAllEnabled();
            JOptionPane.showMessageDialog(mainFrame, "Reconnecting enabled MCP servers and discovering tools...", "MCP Reconnect", JOptionPane.INFORMATION_MESSAGE);
        });

        mcpSubMenu.add(configureMcpItem);
        mcpSubMenu.add(reconnectMcpItem);

        // Sessions & Context Submenu
        JMenu sessionsSubMenu = new JMenu("Multi-Agent Sessions");
        sessionsSubMenu.setIcon(UIUtils.createSparkleIcon(14, UIUtils.ACCENT_COLOR));

        JMenuItem newSessionItem = new JMenuItem("New Agent Session...");
        newSessionItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        newSessionItem.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(mainFrame, "Enter agent session name:", "New Agent Session", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.isBlank()) {
                com.github.agentforge.agentic.ide.agent.session.SessionManager.getInstance()
                        .createSession(name.trim(), "ANTHROPIC", "claude-3-7-sonnet");
            }
        });

        JMenuItem compressContextItem = new JMenuItem("Compress Context Now (95% Utility)...");
        compressContextItem.addActionListener(e -> {
            var session = com.github.agentforge.agentic.ide.agent.session.SessionManager.getInstance().getActiveSession();
            if (session != null) {
                var res = com.github.agentforge.agentic.ide.agent.session.ContextCompressor.compressIfExceeded(session, 0.0);
                JOptionPane.showMessageDialog(mainFrame, res.summary(), "Context Compression", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        sessionsSubMenu.add(newSessionItem);
        sessionsSubMenu.add(compressContextItem);

        JMenuItem settingsItem = new JMenuItem("Configure Providers, Models & Routing...", UIUtils.createGearIcon(14, null));
        settingsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK));
        settingsItem.addActionListener(e -> openSettingsRunnable.run());

        agentMenu.add(explainItem);
        agentMenu.add(refactorItem);
        agentMenu.add(testGenItem);
        agentMenu.add(debugItem);
        agentMenu.addSeparator();
        agentMenu.add(sessionsSubMenu);
        agentMenu.add(memorySubMenu);
        agentMenu.add(mcpSubMenu);
        agentMenu.addSeparator();
        agentMenu.add(stopAgentItem);
        agentMenu.add(clearAgentItem);
        agentMenu.addSeparator();
        agentMenu.add(settingsItem);
        add(agentMenu);

        // 4. VIEW MENU
        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);

        JMenuItem toggleExpItem = new JMenuItem("Toggle Project Explorer");
        toggleExpItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK));
        toggleExpItem.addActionListener(e -> toggleExplorerRunnable.run());

        JMenuItem toggleAgentItem = new JMenuItem("Toggle AI Agent Dock");
        toggleAgentItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK));
        toggleAgentItem.addActionListener(e -> toggleAgentRunnable.run());

        JMenuItem toggleTermItem = new JMenuItem("Toggle Bottom Console");
        toggleTermItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_3, InputEvent.ALT_DOWN_MASK));
        toggleTermItem.addActionListener(e -> toggleTerminalRunnable.run());

        JMenuItem viewMemoryTabItem = new JMenuItem("Focus Agentic Memory Tab");
        viewMemoryTabItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_4, InputEvent.ALT_DOWN_MASK));
        viewMemoryTabItem.addActionListener(e -> terminalPanel.selectMemoryTab());

        JMenu themeSubMenu = new JMenu("Themes");
        String[] themes = {"FlatLaf Darcula", "FlatLaf Dark", "FlatLaf Light", "IntelliJ Light", "One Dark"};
        for (String th : themes) {
            JMenuItem ti = new JMenuItem(th);
            ti.addActionListener(e -> {
                IdeConfig cfg = ConfigManager.getInstance().getConfig();
                cfg.setTheme(th);
                ConfigManager.getInstance().saveConfig(cfg);
                UIUtils.applyTheme(th, mainFrame);
            });
            themeSubMenu.add(ti);
        }

        JMenuItem zoomIn = new JMenuItem("Zoom In (Font Size)");
        zoomIn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK));
        zoomIn.addActionListener(e -> {
            IdeConfig cfg = ConfigManager.getInstance().getConfig();
            cfg.setFontSize(cfg.getFontSize() + 1);
            ConfigManager.getInstance().saveConfig(cfg);
            editorPanel.setEditorFontSize(cfg.getFontSize());
        });

        JMenuItem zoomOut = new JMenuItem("Zoom Out (Font Size)");
        zoomOut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
        zoomOut.addActionListener(e -> {
            IdeConfig cfg = ConfigManager.getInstance().getConfig();
            cfg.setFontSize(cfg.getFontSize() - 1);
            ConfigManager.getInstance().saveConfig(cfg);
            editorPanel.setEditorFontSize(cfg.getFontSize());
        });

        viewMenu.add(toggleExpItem);
        viewMenu.add(toggleAgentItem);
        viewMenu.add(toggleTermItem);
        viewMenu.add(viewMemoryTabItem);
        viewMenu.addSeparator();
        viewMenu.add(themeSubMenu);
        viewMenu.addSeparator();
        viewMenu.add(zoomIn);
        viewMenu.add(zoomOut);
        add(viewMenu);

        // 5. RUN / BUILD MENU
        JMenu runMenu = new JMenu("Run");
        runMenu.setMnemonic(KeyEvent.VK_R);

        JMenuItem runActiveItem = new JMenuItem("Run Active File", UIUtils.createPlayIcon(14, UIUtils.SUCCESS_COLOR));
        runActiveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK));
        runActiveItem.addActionListener(e -> runActiveFileRunnable.run());

        JMenuItem runMvnTestItem = new JMenuItem("Run Maven Test");
        runMvnTestItem.addActionListener(e -> {
            terminalPanel.appendTerminal("\n$ mvn test\n");
            runCommandInTerminal("mvn test", terminalPanel);
        });

        JMenuItem runMvnPackageItem = new JMenuItem("Run Maven Package");
        runMvnPackageItem.addActionListener(e -> {
            terminalPanel.appendTerminal("\n$ mvn package\n");
            runCommandInTerminal("mvn package", terminalPanel);
        });

        runMenu.add(runActiveItem);
        runMenu.addSeparator();
        runMenu.add(runMvnTestItem);
        runMenu.add(runMvnPackageItem);
        add(runMenu);

        // 6. HELP MENU
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem shortcutsItem = new JMenuItem("Keyboard Shortcuts");
        shortcutsItem.addActionListener(e -> showShortcutsDialog(mainFrame));

        JMenuItem aboutItem = new JMenuItem("About AgentForge IDE");
        aboutItem.addActionListener(e -> showAboutDialog(mainFrame));

        helpMenu.add(shortcutsItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);
        add(helpMenu);
    }

    private void runCommandInTerminal(String cmd, TerminalPanel terminalPanel) {
        new Thread(() -> {
            try {
                File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = isWindows
                        ? new ProcessBuilder("cmd.exe", "/c", cmd)
                        : new ProcessBuilder("bash", "-c", cmd);
                pb.directory(dir);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        terminalPanel.appendTerminal(line + "\n");
                    }
                }
                p.waitFor();
            } catch (Exception ex) {
                terminalPanel.appendTerminal("Command failed: " + ex.getMessage() + "\n");
            }
        }).start();
    }

    private void showShortcutsDialog(JFrame parent) {
        String msg = """
                AgentForge AI Agent IDE - Keyboard Shortcuts:
                
                - Run Agent Prompt:        Ctrl + Enter
                - Import All Memory:       Ctrl + Shift + M
                - Explain Code:            Ctrl + Shift + E
                - Refactor with AI:        Ctrl + Shift + R
                - Generate Tests:          Ctrl + Shift + T
                - Diagnose Bugs:           Ctrl + Shift + D
                - Stop Agent:              Esc
                - Settings / API Keys:     Ctrl + ,
                - Toggle Project Explorer: Alt + 1
                - Toggle AI Agent Panel:   Alt + 2
                - Toggle Bottom Console:   Alt + 3
                - Focus Memory Tab:        Alt + 4
                - Run Active File:         Shift + F10
                - Save File:               Ctrl + S
                - New File:                Ctrl + N
                - Open File:               Ctrl + O
                - Close Tab:               Ctrl + W
                """;
        JOptionPane.showMessageDialog(parent, msg, "Keyboard Shortcuts", JOptionPane.INFORMATION_MESSAGE);
    }

    private void showAboutDialog(JFrame parent) {
        String msg = """
                AgentForge AI Agent IDE v1.0.0
                Package: com.github.agentforge.agentic.ide
                
                Features:
                - Agentic AI Memory (Working, Long-Term, Episodic, Project Rules)
                - Import & Export All Memory from JSON/Markdown/Directories
                - Autonomous Tool Calling (File System, Terminal, Code Refactor, Memory)
                - RSyntaxTextArea Code Editor with Syntax Highlighting
                - Modern FlatLaf IntelliJ & Dark UI Themes
                - LangChain4j + OpenAI / Ollama / Gemini Support
                - Built-in Offline Mock Agent Simulator
                """;
        JOptionPane.showMessageDialog(parent, msg, "About AgentForge AI IDE", JOptionPane.INFORMATION_MESSAGE);
    }
}

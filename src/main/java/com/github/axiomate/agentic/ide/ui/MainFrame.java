package com.github.axiomate.agentic.ide.ui;

import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.config.ProjectState;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.interop.AgentCommandInterop;
import com.github.axiomate.agentic.ide.interop.CodingAgent;
import com.github.axiomate.agentic.ide.plugins.InstalledPlugin;
import com.github.axiomate.agentic.ide.plugins.PluginManager;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import com.github.axiomate.agentic.ide.ui.components.AIAgentPanel;
import com.github.axiomate.agentic.ide.ui.components.ActivityBar;
import com.github.axiomate.agentic.ide.ui.components.AgentSyncPanel;
import com.github.axiomate.agentic.ide.ui.components.EditorPanel;
import com.github.axiomate.agentic.ide.ui.components.PluginsPanel;
import com.github.axiomate.agentic.ide.ui.components.ProjectTreePanel;
import com.github.axiomate.agentic.ide.ui.components.SessionsPanel;
import com.github.axiomate.agentic.ide.ui.components.SettingsDialog;
import com.github.axiomate.agentic.ide.ui.components.StatusBar;
import com.github.axiomate.agentic.ide.ui.components.TerminalPanel;
import com.github.axiomate.agentic.ide.ui.components.ToolBar;
import com.github.axiomate.agentic.ide.ui.dialogs.AgentMemoryExportDialog;
import com.github.axiomate.agentic.ide.ui.dialogs.AgentMemoryImportDialog;
import com.github.axiomate.agentic.ide.ui.dialogs.CommandPalette;
import com.github.axiomate.agentic.ide.ui.dialogs.ExternalSessionsDialog;
import com.github.axiomate.agentic.ide.ui.dialogs.McpInteropDialog;
import com.github.axiomate.agentic.ide.ui.dialogs.PluginManagerDialog;
import com.github.axiomate.agentic.ide.ui.dialogs.SessionManagerDialog;
import com.github.axiomate.agentic.ide.ui.menu.AppMenuBar;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Main application window for Axiomate AI Agent IDE.
 */
public class MainFrame extends JFrame implements IdeActions {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private static final int SIDEBAR_WIDTH = 270;

    private final EditorPanel editorPanel;
    private final ProjectTreePanel projectTreePanel;
    private final AIAgentPanel aiAgentPanel;
    private final TerminalPanel terminalPanel;
    private final StatusBar statusBar;
    private final ToolBar toolBar;
    private final ActivityBar activityBar;
    private final JPanel sidebar;
    private final CardLayout sidebarCards = new CardLayout();

    private final JSplitPane mainHorizontalSplit;
    private final JSplitPane centerRightSplit;
    private final JSplitPane verticalBottomSplit;
    private int lastSidebarWidth = SIDEBAR_WIDTH;

    public MainFrame() {
        super("Axiomate AI Agent IDE");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1440, 900);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);

        // Plugins register their slash commands; memories and MCP servers are already persisted
        PluginManager pluginManager = PluginManager.getInstance();

        // Core components
        editorPanel = new EditorPanel();
        terminalPanel = new TerminalPanel();
        terminalPanel.getMemoryPanel().setIdeActions(this);
        aiAgentPanel = new AIAgentPanel(editorPanel::getActiveText, terminalPanel);
        aiAgentPanel.setIdeActions(this);

        projectTreePanel = new ProjectTreePanel(
                editorPanel::openFile,
                file -> {
                    editorPanel.openFile(file);
                    aiAgentPanel.sendPromptDirectly("Explain the file " + file.getName() + " and its architectural role.");
                }
        );

        statusBar = new StatusBar();
        statusBar.setOnProjectClick(this::openSessionManager);
        statusBar.setOnMemoryClick(this::focusMemoryTab);
        statusBar.setOnPluginsClick(() -> showSidebarView(VIEW_PLUGINS));
        statusBar.setOnSessionClick(() -> showSidebarView(VIEW_SESSIONS));
        Runnable pluginCount = () -> statusBar.setPluginCount(
                (int) pluginManager.getInstalled().stream().filter(InstalledPlugin::enabled).count());
        pluginManager.addChangeListener(pluginCount);
        pluginCount.run();

        toolBar = new ToolBar(
                () -> {
                    String name = JOptionPane.showInputDialog(this, "Enter file name:", "New File", JOptionPane.PLAIN_MESSAGE);
                    if (name != null && !name.isBlank()) {
                        editorPanel.newFile(name.trim(), "");
                    }
                },
                () -> {
                    JFileChooser chooser = new JFileChooser(ProjectManager.getInstance().getCurrentProjectDirectory());
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        editorPanel.openFile(chooser.getSelectedFile());
                    }
                },
                editorPanel::saveActiveFile,
                this::runActiveFile,
                () -> {
                    terminalPanel.selectMemoryTab();
                    terminalPanel.getMemoryPanel().importMemories();
                },
                aiAgentPanel::sendPromptDirectly,
                this::openSettings,
                this
        );

        // Menu Bar
        AppMenuBar menuBar = new AppMenuBar(
                this,
                editorPanel,
                terminalPanel,
                aiAgentPanel::sendPromptDirectly,
                () -> aiAgentPanel.sendPromptDirectly("stop"),
                aiAgentPanel::clearChat,
                this::openSettings,
                this::openMcpSettings,
                this::toggleExplorer,
                this::toggleAgent,
                this::toggleTerminal,
                this::runActiveFile
        );
        setJMenuBar(menuBar);

        // Caret position listener for status bar
        Timer caretTimer = new Timer(200, e -> {
            RSyntaxTextArea active = editorPanel.getActiveEditor();
            if (active != null) {
                int line = active.getCaretLineNumber() + 1;
                int col = active.getCaretOffsetFromLineStart();
                statusBar.updateCaretPosition(line, col);
            }
        });
        caretTimer.start();

        // Sidebar views switched by the activity bar
        sidebar = new JPanel(sidebarCards);
        sidebar.add(projectTreePanel, VIEW_EXPLORER);
        sidebar.add(new SessionsPanel(this), VIEW_SESSIONS);
        sidebar.add(new AgentSyncPanel(this), VIEW_AGENT_SYNC);
        sidebar.add(new PluginsPanel(this, pluginManager), VIEW_PLUGINS);
        sidebar.setMinimumSize(new Dimension(200, 0));

        activityBar = new ActivityBar(id -> {
            if (id == null) {
                setSidebarVisible(false);
            } else {
                showSidebarView(id);
            }
        });
        activityBar.addView(VIEW_EXPLORER, "Explorer (Alt+1)", UIUtils.Glyph.EXPLORER);
        activityBar.addView(VIEW_SESSIONS, "Agent Sessions (Alt+5)", UIUtils.Glyph.SESSIONS);
        activityBar.addView(VIEW_AGENT_SYNC, "Agent Sync — Claude Code, Codex, Cursor, Antigravity… (Alt+6)", UIUtils.Glyph.SYNC);
        activityBar.addView(VIEW_PLUGINS, "Plugins (Alt+7)", UIUtils.Glyph.PLUGINS);
        activityBar.addAction("Agentic memory (Alt+4)", UIUtils.Glyph.MEMORY, this::focusMemoryTab);
        activityBar.addAction("Command palette (Ctrl+K)", UIUtils.Glyph.COMMAND, this::openCommandPalette);
        activityBar.addAction("Settings (Ctrl+,)", UIUtils.Glyph.MORE, this::openSettings);
        activityBar.select(VIEW_EXPLORER);

        // Center-Right split: Editor (left) vs AI Agent (right)
        editorPanel.setMinimumSize(new Dimension(320, 120));
        aiAgentPanel.setMinimumSize(new Dimension(380, 120));
        centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPanel, aiAgentPanel);
        centerRightSplit.setResizeWeight(0.62);
        centerRightSplit.setContinuousLayout(true);
        centerRightSplit.setBorder(null);

        // Main Horizontal split: Sidebar (left) vs CenterRightSplit (right)
        mainHorizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, centerRightSplit);
        mainHorizontalSplit.setResizeWeight(0.0);
        mainHorizontalSplit.setDividerLocation(SIDEBAR_WIDTH);
        mainHorizontalSplit.setContinuousLayout(true);
        mainHorizontalSplit.setBorder(null);

        // Vertical Bottom split: Main Horizontal (top) vs TerminalPanel (bottom)
        verticalBottomSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainHorizontalSplit, terminalPanel);
        verticalBottomSplit.setResizeWeight(0.75);
        verticalBottomSplit.setContinuousLayout(true);
        verticalBottomSplit.setBorder(null);

        JPanel workbench = new JPanel(new BorderLayout());
        workbench.add(activityBar, BorderLayout.WEST);
        workbench.add(verticalBottomSplit, BorderLayout.CENTER);

        JPanel contentPane = new JPanel(new BorderLayout());
        contentPane.add(toolBar, BorderLayout.NORTH);
        contentPane.add(workbench, BorderLayout.CENTER);
        contentPane.add(statusBar, BorderLayout.SOUTH);

        setContentPane(contentPane);
        installGlobalShortcuts();

        // Project changes rescope memory, reload the other agents' commands and update the title
        ProjectManager.getInstance().addProjectChangeListener(dir -> {
            MemoryManager.getInstance().setActiveProject(dir);
            reloadAgentCommands();
            SwingUtilities.invokeLater(this::updateTitle);
        });

        restoreSavedProjectStateOrInitial();
        setupProjectStateHooks();
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        MemoryManager.getInstance().setActiveProject(dir);
        reloadAgentCommands();
        updateTitle();
    }

    private void installGlobalShortcuts() {
        JRootPane root = getRootPane();
        // Ctrl+K and Alt+5..7 are menu accelerators; F1 is an extra alias for the command palette
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "palette", this::openCommandPalette);
    }

    private static void bind(JRootPane root, KeyStroke ks, String name, Runnable r) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    private void updateTitle() {
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        setTitle((dir != null ? dir.getName() + " — " : "") + "Axiomate AI Agent IDE");
    }

    private void restoreSavedProjectStateOrInitial() {
        ProjectStateManager stateManager = ProjectStateManager.getInstance();
        String lastPath = stateManager.getLastOpenProjectPath();
        File targetDir = null;
        if (lastPath != null && !lastPath.isBlank()) {
            File dir = new File(lastPath);
            if (dir.exists() && dir.isDirectory()) {
                targetDir = dir;
            }
        }
        if (targetDir == null) {
            targetDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        }

        if (targetDir != null && targetDir.exists() && targetDir.isDirectory()) {
            ProjectManager.getInstance().setCurrentProjectDirectory(targetDir);
            SessionManager.getInstance().loadSessionsForProject(targetDir);
            boolean restored = restoreProjectTabs(targetDir);
            if (!restored) {
                loadInitialSample();
            }
        } else {
            loadInitialSample();
        }
    }

    public boolean restoreProjectTabs(File projectDir) {
        if (projectDir == null) return false;
        ProjectState state = ProjectStateManager.getInstance().getProjectState(projectDir);
        if (state == null || state.getOpenFiles() == null || state.getOpenFiles().isEmpty()) {
            return false;
        }

        int openedCount = 0;
        for (String filePath : state.getOpenFiles()) {
            File file = new File(filePath);
            if (file.exists() && !file.isDirectory()) {
                editorPanel.openFile(file);
                openedCount++;
            }
        }

        if (state.getActiveFile() != null && !state.getActiveFile().isBlank()) {
            File active = new File(state.getActiveFile());
            if (active.exists()) {
                editorPanel.selectFile(active);
            }
        }

        return openedCount > 0;
    }

    public void openProjectDirectory(File newDir) {
        if (newDir == null || !newDir.exists() || !newDir.isDirectory()) return;

        File oldDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (oldDir != null) {
            SessionManager.getInstance().saveSessionsForProject(oldDir);
            ProjectStateManager.getInstance().saveProjectState(oldDir, editorPanel.getOpenFiles(), editorPanel.getActiveFile(),
                    SessionManager.getInstance().getSessions(),
                    SessionManager.getInstance().getActiveSession() != null ? SessionManager.getInstance().getActiveSession().getId() : "");
        }

        editorPanel.closeAllTabs();
        ProjectManager.getInstance().setCurrentProjectDirectory(newDir);
        SessionManager.getInstance().loadSessionsForProject(newDir);

        boolean restored = restoreProjectTabs(newDir);
        if (!restored) {
            loadInitialSample();
        }
        // Record the project right away so it shows up in Open Recent and the Session Manager
        saveCurrentProjectState();
    }

    public void closeProjectDirectory() {
        File currentDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (currentDir != null) {
            SessionManager.getInstance().saveSessionsForProject(currentDir);
            ProjectStateManager.getInstance().closeProject(currentDir, editorPanel.getOpenFiles(), editorPanel.getActiveFile());
        }
        editorPanel.closeAllTabs();
        ProjectManager.getInstance().setActiveFile(null);
    }

    public void saveCurrentProjectState() {
        File currentDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (currentDir != null) {
            SessionManager.getInstance().saveSessionsForProject(currentDir);
            ProjectStateManager.getInstance().saveProjectState(currentDir, editorPanel.getOpenFiles(), editorPanel.getActiveFile(),
                    SessionManager.getInstance().getSessions(),
                    SessionManager.getInstance().getActiveSession() != null ? SessionManager.getInstance().getActiveSession().getId() : "");
        }
    }

    private void setupProjectStateHooks() {
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                // The agent dock's preferred width would otherwise squeeze the editor; split evenly once sized
                SwingUtilities.invokeLater(() -> {
                    centerRightSplit.setDividerLocation(0.5);
                    verticalBottomSplit.setDividerLocation(0.72);
                });
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                saveCurrentProjectState();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveCurrentProjectState));
    }

    private void loadInitialSample() {
        File sampleFile = new File(ProjectManager.getInstance().getCurrentProjectDirectory(),
                "src/main/resources/sample-project/Calculator.java");
        if (sampleFile.exists()) {
            editorPanel.openFile(sampleFile);
        } else {
            File[] javaFiles = ProjectManager.getInstance().getCurrentProjectDirectory().listFiles((d, n) -> n.endsWith(".java"));
            if (javaFiles != null && javaFiles.length > 0) {
                editorPanel.openFile(javaFiles[0]);
            } else {
                editorPanel.newFile("Welcome.java", """
                        // Welcome to Axiomate AI Agent IDE!
                        public class Welcome {
                            public static void main(String[] args) {
                                System.out.println("Axiomate AI Agent IDE with Agentic Memory is ready.");
                            }
                        }
                        """);
            }
        }
    }

    public void runActiveFile() {
        File activeFile = ProjectManager.getInstance().getActiveFile();
        if (activeFile == null) {
            JOptionPane.showMessageDialog(this, "No active file selected to run.", "Run File", JOptionPane.WARNING_MESSAGE);
            return;
        }

        editorPanel.saveActiveFile();
        String fileName = activeFile.getName();
        terminalPanel.appendBuildOutput(">>> Compiling & Running " + fileName + " <<<\n");

        new Thread(() -> {
            try {
                File dir = activeFile.getParentFile();
                if (fileName.endsWith(".java")) {
                    ProcessBuilder compilePb = new ProcessBuilder("javac", activeFile.getAbsolutePath());
                    compilePb.directory(dir);
                    compilePb.redirectErrorStream(true);
                    Process cp = compilePb.start();
                    readProcessOutput(cp);
                    int compExit = cp.waitFor();
                    if (compExit != 0) {
                        terminalPanel.appendBuildOutput("[Compilation Failed with exit code " + compExit + "]\n");
                        return;
                    }

                    String className = fileName.substring(0, fileName.lastIndexOf('.'));
                    ProcessBuilder runPb = new ProcessBuilder("java", className);
                    runPb.directory(dir);
                    runPb.redirectErrorStream(true);
                    Process rp = runPb.start();
                    readProcessOutput(rp);
                    int runExit = rp.waitFor();
                    terminalPanel.appendBuildOutput("[Program exited with code " + runExit + "]\n");
                } else if (fileName.endsWith(".py")) {
                    ProcessBuilder pb = new ProcessBuilder("python", activeFile.getAbsolutePath());
                    pb.directory(dir);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    readProcessOutput(p);
                    p.waitFor();
                } else {
                    terminalPanel.appendBuildOutput("Direct execution not supported for file type: " + fileName + "\n");
                }
            } catch (Exception e) {
                terminalPanel.appendBuildOutput("Execution error: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private void readProcessOutput(Process process) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                terminalPanel.appendBuildOutput(line + "\n");
            }
        }
    }

    public void openSettings() {
        openSettings(0);
    }

    public void openSettings(int tabIndex) {
        SettingsDialog dialog = new SettingsDialog(this, tabIndex);
        dialog.setVisible(true);
    }

    public void openMcpSettings() {
        openSettings(SettingsDialog.TAB_MCP);
    }

    // ------------------------------------------------------------------
    // Sidebar & panels
    // ------------------------------------------------------------------

    private void setSidebarVisible(boolean visible) {
        if (!visible && sidebar.isVisible()) {
            lastSidebarWidth = Math.max(200, mainHorizontalSplit.getDividerLocation());
            activityBar.select(null);
        }
        sidebar.setVisible(visible);
        if (visible) {
            mainHorizontalSplit.setDividerLocation(lastSidebarWidth);
        }
        mainHorizontalSplit.revalidate();
    }

    @Override
    public void showSidebarView(String viewId) {
        sidebarCards.show(sidebar, viewId);
        activityBar.select(viewId);
        if (!sidebar.isVisible()) {
            setSidebarVisible(true);
        }
    }

    public void toggleExplorer() {
        if (sidebar.isVisible() && VIEW_EXPLORER.equals(activityBar.getSelectedId())) {
            setSidebarVisible(false);
        } else {
            showSidebarView(VIEW_EXPLORER);
        }
    }

    public void toggleAgent() {
        boolean visible = aiAgentPanel.isVisible();
        aiAgentPanel.setVisible(!visible);
        centerRightSplit.resetToPreferredSizes();
    }

    public void toggleTerminal() {
        boolean visible = terminalPanel.isVisible();
        terminalPanel.setVisible(!visible);
        verticalBottomSplit.resetToPreferredSizes();
    }

    // ------------------------------------------------------------------
    // IdeActions
    // ------------------------------------------------------------------

    private Path projectPath() {
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        return dir != null ? dir.toPath() : null;
    }

    @Override
    public void openProject(File dir) {
        openProjectDirectory(dir);
    }

    @Override
    public void openSessionManager() {
        new SessionManagerDialog(this, this).setVisible(true);
    }

    @Override
    public void openExternalSessionImport() {
        new ExternalSessionsDialog(this, projectPath()).setVisible(true);
    }

    @Override
    public void openMemoryImport() {
        new AgentMemoryImportDialog(this, projectPath()).setVisible(true);
    }

    @Override
    public void openMemoryExport() {
        new AgentMemoryExportDialog(this, projectPath()).setVisible(true);
    }

    @Override
    public void openMcpInterop(boolean exportTab) {
        new McpInteropDialog(this, projectPath(), exportTab).setVisible(true);
    }

    @Override
    public void openPluginManager(int tab) {
        new PluginManagerDialog(this, PluginManager.getInstance(), projectPath(), this, tab).setVisible(true);
    }

    @Override
    public void focusMemoryTab() {
        if (!terminalPanel.isVisible()) toggleTerminal();
        terminalPanel.selectMemoryTab();
    }

    /**
     * Re-reads slash commands from other coding agents (Claude Code commands, Codex prompts, Antigravity
     * workflows, Gemini CLI commands...) for the current project.
     */
    @Override
    public void reloadAgentCommands() {
        Path project = projectPath();
        CompletableFuture.supplyAsync(() -> new AgentCommandInterop().discover(project)).thenAccept(commands -> {
            SlashCommandRegistry reg = SlashCommandRegistry.getInstance();
            for (CodingAgent a : CodingAgent.values()) {
                reg.unregisterSource(AgentCommandInterop.sourceKey(a));
            }
            reg.registerAll(commands);
            if (!commands.isEmpty()) {
                log.info("Loaded {} slash command(s) from other coding agents", commands.size());
            }
        });
    }

    @Override
    public void openCommandPalette() {
        List<CommandPalette.Entry> entries = new ArrayList<>();
        entries.add(new CommandPalette.Entry("View: Explorer", "View", "Alt+1", () -> showSidebarView(VIEW_EXPLORER)));
        entries.add(new CommandPalette.Entry("View: Agent Sessions", "View", "Alt+5", () -> showSidebarView(VIEW_SESSIONS)));
        entries.add(new CommandPalette.Entry("View: Agent Sync (Claude Code, Codex, Cursor, Antigravity…)", "View", "Alt+6",
                () -> showSidebarView(VIEW_AGENT_SYNC)));
        entries.add(new CommandPalette.Entry("View: Plugins", "View", "Alt+7", () -> showSidebarView(VIEW_PLUGINS)));
        entries.addAll(CommandPalette.fromMenuBar(getJMenuBar()));
        for (AgentSession s : SessionManager.getInstance().getSessions()) {
            entries.add(new CommandPalette.Entry("Switch to session: " + s.getName(), "Session", s.getModelId(),
                    () -> SessionManager.getInstance().switchSession(s.getId())));
        }
        for (ProjectState p : ProjectStateManager.getInstance().getKnownProjects()) {
            File dir = new File(p.getProjectPath());
            if (dir.isDirectory()) {
                entries.add(new CommandPalette.Entry("Open project: " + p.getProjectName(), "Project", p.getProjectPath(),
                        () -> openProjectDirectory(dir)));
            }
        }
        for (SlashCommand c : SlashCommandRegistry.getInstance().all()) {
            entries.add(new CommandPalette.Entry("/" + c.name() + " — " + c.description(), "Command", null, () -> {
                if (!aiAgentPanel.isVisible()) toggleAgent();
                if (c.isAction()) {
                    c.action().accept("");
                } else {
                    aiAgentPanel.prefillPrompt("/" + c.name() + " ");
                }
            }));
        }
        new CommandPalette(this, entries).setVisible(true);
    }
}

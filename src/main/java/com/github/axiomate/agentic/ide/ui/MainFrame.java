package com.github.axiomate.agentic.ide.ui;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ProjectState;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.ui.components.AIAgentPanel;
import com.github.axiomate.agentic.ide.ui.components.EditorPanel;
import com.github.axiomate.agentic.ide.ui.components.ProjectTreePanel;
import com.github.axiomate.agentic.ide.ui.components.SettingsDialog;
import com.github.axiomate.agentic.ide.ui.components.StatusBar;
import com.github.axiomate.agentic.ide.ui.components.TerminalPanel;
import com.github.axiomate.agentic.ide.ui.components.ToolBar;
import com.github.axiomate.agentic.ide.ui.menu.AppMenuBar;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Main application window for AgentForge AI Agent IDE.
 */
public class MainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    private final EditorPanel editorPanel;
    private final ProjectTreePanel projectTreePanel;
    private final AIAgentPanel aiAgentPanel;
    private final TerminalPanel terminalPanel;
    private final StatusBar statusBar;
    private final ToolBar toolBar;

    private final JSplitPane mainHorizontalSplit;
    private final JSplitPane centerRightSplit;
    private final JSplitPane verticalBottomSplit;

    public MainFrame() {
        super("Axiomate AI Agent IDE - Autonomous Coding Environment");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 900);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);

        // Core components
        editorPanel = new EditorPanel();
        terminalPanel = new TerminalPanel();
        aiAgentPanel = new AIAgentPanel(editorPanel::getActiveText, terminalPanel);

        projectTreePanel = new ProjectTreePanel(
                editorPanel::openFile,
                file -> {
                    editorPanel.openFile(file);
                    aiAgentPanel.sendPromptDirectly("Explain the file " + file.getName() + " and its architectural role.");
                }
        );

        statusBar = new StatusBar();

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
                this::openSettings
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

        // Center-Right split: Editor (left) vs AI Agent (right)
        centerRightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPanel, aiAgentPanel);
        centerRightSplit.setResizeWeight(0.70);
        centerRightSplit.setContinuousLayout(true);
        centerRightSplit.setBorder(null);

        // Main Horizontal split: ProjectTree (left) vs CenterRightSplit (right)
        mainHorizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectTreePanel, centerRightSplit);
        mainHorizontalSplit.setResizeWeight(0.18);
        mainHorizontalSplit.setContinuousLayout(true);
        mainHorizontalSplit.setBorder(null);

        // Vertical Bottom split: Main Horizontal (top) vs TerminalPanel (bottom)
        verticalBottomSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainHorizontalSplit, terminalPanel);
        verticalBottomSplit.setResizeWeight(0.75);
        verticalBottomSplit.setContinuousLayout(true);
        verticalBottomSplit.setBorder(null);

        JPanel contentPane = new JPanel(new BorderLayout());
        contentPane.add(toolBar, BorderLayout.NORTH);
        contentPane.add(verticalBottomSplit, BorderLayout.CENTER);
        contentPane.add(statusBar, BorderLayout.SOUTH);

        setContentPane(contentPane);

        restoreSavedProjectStateOrInitial();
        setupProjectStateHooks();
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
        openSettings(1);
    }

    public void toggleExplorer() {
        boolean visible = projectTreePanel.isVisible();
        projectTreePanel.setVisible(!visible);
        mainHorizontalSplit.resetToPreferredSizes();
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
}


package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;

/**
 * Bottom status bar: project, active file, caret position, and clickable indicators for the active agent
 * session, token usage, agent memory and plugins.
 */
public class StatusBar extends JPanel {

    private final JLabel projectLabel;
    private final JLabel fileLabel;
    private final JLabel caretLabel;
    private final JLabel memoryLabel;
    private final JLabel pluginsLabel;
    private final JLabel tokenStatusLabel;
    private final JLabel modelLabel;
    private double lastTokenPct;

    public StatusBar() {
        setLayout(new BorderLayout());

        projectLabel = segment("", "Current project — click for the Session Manager");
        projectLabel.setFont(UIUtils.uiFont(Font.BOLD, 11f));
        fileLabel = segment("No file open", null);
        caretLabel = segment("Ln 1, Col 1", null);
        memoryLabel = segment("0 memories", "Agent memories visible to this project — click to browse");
        pluginsLabel = segment("0 plugins", "Enabled plugins — click to manage");
        tokenStatusLabel = segment("Tokens: 0 / 128k (0.0%)", "Context usage of the active session");
        modelLabel = segment("AI: Mock Simulator", "Active agent session — click to switch sessions");
        modelLabel.setIcon(UIUtils.createSparkleIcon(12, UIUtils.ACCENT_PURPLE));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        leftPanel.setOpaque(false);
        leftPanel.add(projectLabel);
        leftPanel.add(fileLabel);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        rightPanel.setOpaque(false);
        rightPanel.add(caretLabel);
        rightPanel.add(pluginsLabel);
        rightPanel.add(memoryLabel);
        rightPanel.add(tokenStatusLabel);
        rightPanel.add(modelLabel);

        add(leftPanel, BorderLayout.WEST);
        add(rightPanel, BorderLayout.EAST);

        applyColors();
        UIUtils.addThemeListener(this::applyColors);

        ProjectManager.getInstance().addActiveFileChangeListener(this::updateActiveFile);
        ProjectManager.getInstance().addProjectChangeListener(dir -> SwingUtilities.invokeLater(this::updateProject));
        ConfigManager.getInstance().addListener(cfg -> UIUtils.onEdt(() -> updateConfig(cfg)));

        MemoryManager.getInstance().addChangeListener(this::updateMemoryCount);
        updateMemoryCount();

        SessionManager.getInstance().addSessionChangeListener(this::updateSessionAndTokens);
        updateSessionAndTokens();
        updateProject();
    }

    private JLabel segment(String text, String tooltip) {
        JLabel l = new JLabel(text);
        l.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        l.setToolTipText(tooltip);
        return l;
    }

    private void applyColors() {
        setBackground(UIUtils.surface(2));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIUtils.borderColor()),
                new EmptyBorder(3, 10, 3, 10)));
        for (JLabel l : new JLabel[]{projectLabel, fileLabel, caretLabel, pluginsLabel}) {
            l.setForeground(UIUtils.mutedForeground());
        }
        projectLabel.setIcon(UIUtils.createFolderIcon(12, null));
        pluginsLabel.setIcon(UIUtils.glyph(UIUtils.Glyph.PLUGINS, 12, UIUtils.mutedForeground()));
        memoryLabel.setIcon(UIUtils.glyph(UIUtils.Glyph.MEMORY, 12, UIUtils.accentText(UIUtils.ACCENT_PURPLE)));
        projectLabel.setForeground(UIUtils.foreground());
        memoryLabel.setForeground(UIUtils.accentText(UIUtils.ACCENT_PURPLE));
        modelLabel.setForeground(UIUtils.accentText(UIUtils.ACCENT_PURPLE));
        colorTokens();
    }

    private static void onClick(JLabel label, Runnable action) {
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        });
    }

    public void setOnProjectClick(Runnable action) {
        onClick(projectLabel, action);
    }

    public void setOnMemoryClick(Runnable action) {
        onClick(memoryLabel, action);
    }

    public void setOnPluginsClick(Runnable action) {
        onClick(pluginsLabel, action);
    }

    public void setOnSessionClick(Runnable action) {
        onClick(modelLabel, action);
    }

    public void setOnTokensClick(Runnable action) {
        onClick(tokenStatusLabel, action);
    }

    public void setPluginCount(int enabled) {
        SwingUtilities.invokeLater(() -> pluginsLabel.setText(enabled + (enabled == 1 ? " plugin" : " plugins")));
    }

    private void updateProject() {
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        projectLabel.setText(dir != null ? dir.getName() : "No project");
        projectLabel.setToolTipText(dir != null ? dir.getAbsolutePath() + " — click for the Session Manager" : null);
    }

    public void updateActiveFile(File file) {
        if (file != null) {
            fileLabel.setText(file.getName());
            fileLabel.setToolTipText(file.getAbsolutePath());
        } else {
            fileLabel.setText("No file open");
            fileLabel.setToolTipText(null);
        }
    }

    public void updateCaretPosition(int line, int col) {
        caretLabel.setText("Ln " + line + ", Col " + col);
    }

    public void updateMemoryCount() {
        SwingUtilities.invokeLater(() -> {
            String scope = MemoryManager.getInstance().getActiveProjectPath();
            long count = MemoryManager.getInstance().getMemoryStore().getAllMemories().stream()
                    .filter(m -> m.isVisibleInScope(scope)).count();
            memoryLabel.setText(count + (count == 1 ? " memory" : " memories"));
        });
    }

    public void updateSessionAndTokens() {
        SwingUtilities.invokeLater(() -> {
            AgentSession session = SessionManager.getInstance().getActiveSession();
            if (session != null) {
                TokenTracker tracker = session.getTokenTracker();
                lastTokenPct = tracker.getUsagePercentage();
                tokenStatusLabel.setText(String.format("Tokens: %,d / %,d (%.1f%%)",
                        tracker.getContextTokens(), tracker.getMaxContextTokens(), lastTokenPct));
                colorTokens();
                int count = SessionManager.getInstance().getSessions().size();
                modelLabel.setText(String.format("%s · %s/%s%s", session.getName(), session.getProviderId(),
                        session.getModelId(), count > 1 ? "  (" + count + " sessions)" : ""));
            }
        });
    }

    private void colorTokens() {
        if (lastTokenPct >= 95.0) {
            tokenStatusLabel.setForeground(UIUtils.ERROR_COLOR);
        } else if (lastTokenPct >= 85.0) {
            tokenStatusLabel.setForeground(UIUtils.accentText(UIUtils.WARNING_COLOR));
        } else {
            tokenStatusLabel.setForeground(UIUtils.mutedForeground());
        }
    }

    public void updateConfig(IdeConfig config) {
        updateSessionAndTokens();
    }
}

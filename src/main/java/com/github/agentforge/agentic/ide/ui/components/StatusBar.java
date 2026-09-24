package com.github.agentforge.agentic.ide.ui.components;

import com.github.agentforge.agentic.ide.agent.memory.MemoryManager;
import com.github.agentforge.agentic.ide.agent.session.AgentSession;
import com.github.agentforge.agentic.ide.agent.session.SessionManager;
import com.github.agentforge.agentic.ide.agent.session.TokenTracker;
import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.ui.util.UIUtils;
import com.github.agentforge.agentic.ide.util.ProjectManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;

/**
 * Bottom status bar displaying file path, cursor coordinates, active AI agent session,
 * real-time token consumption / context limit percentage, and memory count.
 */
public class StatusBar extends JPanel {

    private final JLabel fileLabel;
    private final JLabel caretLabel;
    private final JLabel memoryLabel;
    private final JLabel tokenStatusLabel;
    private final JLabel modelLabel;

    public StatusBar() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(4, 12, 4, 12));
        setBackground(new Color(28, 29, 34));

        fileLabel = new JLabel("No file open");
        fileLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        fileLabel.setForeground(Color.LIGHT_GRAY);

        caretLabel = new JLabel("Ln 1, Col 1");
        caretLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        caretLabel.setForeground(Color.LIGHT_GRAY);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        rightPanel.setOpaque(false);

        memoryLabel = new JLabel("🧠 Memory: 0");
        memoryLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        memoryLabel.setForeground(UIUtils.ACCENT_PURPLE);

        tokenStatusLabel = new JLabel("Tokens: 0 / 128k (0.0%)");
        tokenStatusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tokenStatusLabel.setForeground(new Color(170, 210, 255));

        modelLabel = new JLabel("AI: Mock Simulator", UIUtils.createSparkleIcon(12, UIUtils.ACCENT_PURPLE), JLabel.LEFT);
        modelLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modelLabel.setForeground(UIUtils.ACCENT_PURPLE);

        rightPanel.add(caretLabel);
        rightPanel.add(memoryLabel);
        rightPanel.add(tokenStatusLabel);
        rightPanel.add(modelLabel);

        add(fileLabel, BorderLayout.WEST);
        add(rightPanel, BorderLayout.EAST);

        ProjectManager.getInstance().addActiveFileChangeListener(this::updateActiveFile);
        ConfigManager.getInstance().addListener(this::updateConfig);

        // Update memory count
        MemoryManager.getInstance().addChangeListener(this::updateMemoryCount);
        updateMemoryCount();

        // Update session and token tracking
        SessionManager.getInstance().addSessionChangeListener(this::updateSessionAndTokens);
        updateSessionAndTokens();
    }

    public void updateActiveFile(File file) {
        if (file != null) {
            fileLabel.setText(file.getName() + " (" + file.getParent() + ")");
        } else {
            fileLabel.setText("No file open");
        }
    }

    public void updateCaretPosition(int line, int col) {
        caretLabel.setText("Ln " + line + ", Col " + col);
    }

    public void updateMemoryCount() {
        int count = MemoryManager.getInstance().getMemoryStore().getAllMemories().size();
        memoryLabel.setText("🧠 Memories: " + count);
    }

    public void updateSessionAndTokens() {
        SwingUtilities.invokeLater(() -> {
            AgentSession session = SessionManager.getInstance().getActiveSession();
            if (session != null) {
                TokenTracker tracker = session.getTokenTracker();
                double pct = tracker.getUsagePercentage();

                String tokenText = String.format("Tokens: %,d / %,d (%.1f%%)",
                        tracker.getTotalTokens(), tracker.getMaxContextTokens(), pct);
                tokenStatusLabel.setText(tokenText);

                if (pct >= 95.0) {
                    tokenStatusLabel.setForeground(UIUtils.ERROR_COLOR);
                } else if (pct >= 85.0) {
                    tokenStatusLabel.setForeground(new Color(240, 130, 40));
                } else {
                    tokenStatusLabel.setForeground(new Color(170, 210, 255));
                }

                modelLabel.setText(String.format("[%s] %s (%s)",
                        session.getName(), session.getProviderId(), session.getModelId()));
            }
        });
    }

    public void updateConfig(IdeConfig config) {
        updateSessionAndTokens();
    }
}

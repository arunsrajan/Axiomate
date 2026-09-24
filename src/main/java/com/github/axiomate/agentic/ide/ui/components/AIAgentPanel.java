package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AIAgentService;
import com.github.axiomate.agentic.ide.agent.AgentListener;
import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.ContextCompressor;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * Interactive AI Agent dock supporting multi-agent sessions, configurable provider and model
 * selection, real-time token and context limit tracking, and 95% context compression utility.
 */
public class AIAgentPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(AIAgentPanel.class);

    private final JPanel chatBox;
    private final JScrollPane chatScrollPane;
    private final JTextArea inputArea;
    private final JButton sendBtn;
    private final JButton stopBtn;
    private final JLabel statusBadge;
    private final JCheckBox includeContextCheck;
    private final JCheckBox includeMemoryCheck;

    // Multi-Agent Session Controls
    private final JComboBox<String> sessionSelectorCombo;
    private final JButton newSessionBtn;
    private final JButton renameSessionBtn;
    private final JButton closeSessionBtn;

    // Model & Provider Chooser Controls
    private final JComboBox<String> providerCombo;
    private final JComboBox<String> modelCombo;
    private final JCheckBox autoRouteCheck;

    // Token Usage & Context Limit Controls
    private final JLabel tokenUsageLabel;
    private final JProgressBar tokenProgressBar;
    private final JButton compressBtn;

    private final Supplier<String> activeCodeSupplier;
    private final TerminalPanel terminalPanel;
    private final FileMentionController fileMentionController;

    private JPanel currentAssistantMessagePanel;
    private JTextArea currentAssistantTextArea;
    private StringBuilder currentStreamingBuffer;
    private boolean updatingSessionUi = false;

    public AIAgentPanel(Supplier<String> activeCodeSupplier, TerminalPanel terminalPanel) {
        this.activeCodeSupplier = activeCodeSupplier;
        this.terminalPanel = terminalPanel;
        setLayout(new BorderLayout());
        setBorder(new LineBorder(new Color(60, 60, 60), 1));

        // 1. Main Header Panel
        JPanel headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setBorder(new EmptyBorder(8, 12, 6, 12));
        headerPanel.setBackground(new Color(32, 34, 40));

        JPanel titleSubPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleSubPanel.setOpaque(false);
        JLabel iconLabel = new JLabel(UIUtils.createSparkleIcon(16, UIUtils.ACCENT_PURPLE));
        JLabel titleLabel = new JLabel("AXIOMATE AI");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        titleLabel.setForeground(Color.WHITE);

        statusBadge = new JLabel("● Ready");
        statusBadge.setFont(new Font("SansSerif", Font.BOLD, 11));
        statusBadge.setForeground(UIUtils.SUCCESS_COLOR);

        titleSubPanel.add(iconLabel);
        titleSubPanel.add(titleLabel);
        titleSubPanel.add(Box.createHorizontalStrut(8));
        titleSubPanel.add(statusBadge);

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headerButtons.setOpaque(false);

        JButton importMemBtn = new JButton("📥 Import Memory");
        importMemBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        importMemBtn.setContentAreaFilled(false);
        importMemBtn.setBorderPainted(false);
        importMemBtn.setFocusPainted(false);
        importMemBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        importMemBtn.addActionListener(e -> {
            terminalPanel.selectMemoryTab();
            terminalPanel.getMemoryPanel().importMemories();
        });

        JButton clearBtn = new JButton("Clear");
        clearBtn.setContentAreaFilled(false);
        clearBtn.setBorderPainted(false);
        clearBtn.setFocusPainted(false);
        clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearBtn.addActionListener(e -> clearChat());

        headerButtons.add(importMemBtn);
        headerButtons.add(clearBtn);

        headerPanel.add(titleSubPanel, BorderLayout.WEST);
        headerPanel.add(headerButtons, BorderLayout.EAST);

        // 2. Session Management Bar
        JPanel sessionBar = new JPanel(new BorderLayout(6, 0));
        sessionBar.setBorder(new EmptyBorder(4, 10, 4, 10));
        sessionBar.setBackground(new Color(28, 30, 36));

        JPanel sessionLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        sessionLeft.setOpaque(false);
        JLabel sessLabel = new JLabel("Session:");
        sessLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        sessLabel.setForeground(Color.LIGHT_GRAY);
        sessionLeft.add(sessLabel);

        sessionSelectorCombo = new JComboBox<>();
        sessionSelectorCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
        sessionSelectorCombo.setPreferredSize(new Dimension(190, 24));
        sessionSelectorCombo.addActionListener(e -> onSessionSelected());
        sessionLeft.add(sessionSelectorCombo);

        newSessionBtn = new JButton("+ New");
        newSessionBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        newSessionBtn.setToolTipText("Launch a new autonomous Agent Session");
        newSessionBtn.addActionListener(e -> promptNewSession());
        sessionLeft.add(newSessionBtn);

        renameSessionBtn = new JButton("Rename");
        renameSessionBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        renameSessionBtn.addActionListener(e -> promptRenameSession());
        sessionLeft.add(renameSessionBtn);

        closeSessionBtn = new JButton("✕");
        closeSessionBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        closeSessionBtn.setToolTipText("Close current session");
        closeSessionBtn.addActionListener(e -> closeActiveSession());
        sessionLeft.add(closeSessionBtn);

        sessionBar.add(sessionLeft, BorderLayout.CENTER);

        // 3. Provider, Model & Token Tracking Bar
        JPanel controlBar = new JPanel();
        controlBar.setLayout(new BoxLayout(controlBar, BoxLayout.Y_AXIS));
        controlBar.setBackground(new Color(24, 25, 30));
        controlBar.setBorder(new CompoundBorder(new LineBorder(new Color(45, 45, 52), 1), new EmptyBorder(4, 10, 6, 10)));

        // Line A: Provider & Model Selector + Auto Route
        JPanel modelLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        modelLine.setOpaque(false);

        JLabel provLabel = new JLabel("Provider:");
        provLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        provLabel.setForeground(Color.LIGHT_GRAY);
        modelLine.add(provLabel);

        providerCombo = new JComboBox<>();
        providerCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
        providerCombo.setPreferredSize(new Dimension(130, 22));
        providerCombo.addActionListener(e -> onProviderChanged());
        modelLine.add(providerCombo);

        JLabel modLabel = new JLabel("Model:");
        modLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modLabel.setForeground(Color.LIGHT_GRAY);
        modelLine.add(modLabel);

        modelCombo = new JComboBox<>();
        modelCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modelCombo.setPreferredSize(new Dimension(160, 22));
        modelCombo.addActionListener(e -> onModelChanged());
        modelLine.add(modelCombo);

        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        autoRouteCheck = new JCheckBox("Auto-Route", cfg.isAutoRoutingEnabled());
        autoRouteCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        autoRouteCheck.setToolTipText("Automatically chooses provider & model per task (Refactor, Tests, Explain, Debug)");
        autoRouteCheck.addActionListener(e -> {
            cfg.setAutoRoutingEnabled(autoRouteCheck.isSelected());
            ConfigManager.getInstance().saveConfig(cfg);
        });
        modelLine.add(autoRouteCheck);

        // Line B: Token Usage Progress Bar & Compression Button
        JPanel tokenLine = new JPanel(new BorderLayout(8, 0));
        tokenLine.setOpaque(false);
        tokenLine.setBorder(new EmptyBorder(2, 0, 0, 0));

        JPanel tokenLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tokenLeft.setOpaque(false);

        tokenUsageLabel = new JLabel("Tokens: 0 / 128,000 (0.0%)");
        tokenUsageLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tokenUsageLabel.setForeground(new Color(180, 210, 240));
        tokenLeft.add(tokenUsageLabel);

        tokenProgressBar = new JProgressBar(0, 100);
        tokenProgressBar.setPreferredSize(new Dimension(120, 14));
        tokenProgressBar.setStringPainted(true);
        tokenProgressBar.setFont(new Font("SansSerif", Font.BOLD, 9));
        tokenProgressBar.setForeground(new Color(60, 180, 75));
        tokenLeft.add(tokenProgressBar);

        compressBtn = new JButton("⚡ Compress (95%)");
        compressBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        compressBtn.setToolTipText("Manually trigger 95% context compression utility to preserve context window");
        compressBtn.addActionListener(e -> triggerManualCompression());

        tokenLine.add(tokenLeft, BorderLayout.WEST);
        tokenLine.add(compressBtn, BorderLayout.EAST);

        controlBar.add(modelLine);
        controlBar.add(tokenLine);

        // 4. Quick Action Chips Panel
        JPanel chipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        chipsPanel.setBorder(new EmptyBorder(2, 8, 4, 8));

        chipsPanel.add(createChip("⚡ Explain", "Explain this code in detail and highlight key logic"));
        chipsPanel.add(createChip("🛠 Refactor", "Refactor and modernize this code for clarity and performance"));
        chipsPanel.add(createChip("🧪 Add Tests", "Generate comprehensive JUnit 5 test cases for this code"));
        chipsPanel.add(createChip("🐛 Find Bugs", "Diagnose potential bugs, security issues, and edge cases"));
        chipsPanel.add(createChip("🧠 View Memory", "Show all project rules and stored agent memories"));

        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.add(headerPanel);
        topContainer.add(sessionBar);
        topContainer.add(controlBar);
        topContainer.add(chipsPanel);
        add(topContainer, BorderLayout.NORTH);

        // 5. Chat Messages Container
        chatBox = new JPanel();
        chatBox.setLayout(new BoxLayout(chatBox, BoxLayout.Y_AXIS));
        chatBox.setBorder(new EmptyBorder(8, 8, 8, 8));

        chatScrollPane = new JScrollPane(chatBox);
        chatScrollPane.setBorder(null);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(chatScrollPane, BorderLayout.CENTER);

        // 6. Input Area at Bottom
        JPanel inputPanel = new JPanel(new BorderLayout(6, 6));
        inputPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        includeContextCheck = new JCheckBox("Active File Context", true);
        includeContextCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));

        includeMemoryCheck = new JCheckBox("Agentic Memory", true);
        includeMemoryCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));

        inputArea = new JTextArea(3, 20);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font("SansSerif", Font.PLAIN, 13));
        inputArea.setBorder(new EmptyBorder(6, 6, 6, 6));
        inputArea.setToolTipText("Type your prompt... Type '@' to mention and inject files from the workspace");

        fileMentionController = new FileMentionController(inputArea);

        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && (e.isControlDown() || e.isMetaDown())) {
                    e.consume();
                    submitPrompt();
                }
            }
        });

        JScrollPane inputScroll = new JScrollPane(inputArea);

        JPanel buttonBar = new JPanel(new BorderLayout());
        JPanel leftBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftBar.add(includeContextCheck);
        leftBar.add(includeMemoryCheck);

        JPanel rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        stopBtn = new JButton("Stop", UIUtils.createStopIcon(12, UIUtils.ERROR_COLOR));
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> cancelAgent());

        sendBtn = UIUtils.createPillButton("Send (Ctrl+↵)", UIUtils.createSparkleIcon(12, Color.WHITE),
                UIUtils.ACCENT_COLOR, Color.WHITE);
        sendBtn.addActionListener(e -> submitPrompt());

        rightBar.add(stopBtn);
        rightBar.add(sendBtn);

        buttonBar.add(leftBar, BorderLayout.WEST);
        buttonBar.add(rightBar, BorderLayout.EAST);

        inputPanel.add(inputScroll, BorderLayout.CENTER);
        inputPanel.add(buttonBar, BorderLayout.SOUTH);

        add(inputPanel, BorderLayout.SOUTH);

        // Register session and configuration change listeners
        SessionManager.getInstance().addSessionChangeListener(this::refreshSessionUi);
        ConfigManager.getInstance().addListener(updatedCfg -> refreshSessionUi());

        // Initial UI population
        refreshSessionUi();
        reloadChatFromSession();
    }

    private void onSessionSelected() {
        if (updatingSessionUi) return;
        int idx = sessionSelectorCombo.getSelectedIndex();
        List<AgentSession> sessions = SessionManager.getInstance().getSessions();
        if (idx >= 0 && idx < sessions.size()) {
            AgentSession selected = sessions.get(idx);
            SessionManager.getInstance().switchSession(selected.getId());
            syncControlsToSession(selected);
            reloadChatFromSession();
        }
    }

    private void onProviderChanged() {
        if (updatingSessionUi) return;
        String provider = (String) providerCombo.getSelectedItem();
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session != null && provider != null) {
            session.setProviderId(provider);
            populateModelsForProvider(provider);

            // Synchronize with global config & AgentManager
            IdeConfig config = ConfigManager.getInstance().getConfig();
            config.setActiveProviderId(provider);
            if (session.getModelId() != null) {
                config.setActiveModelId(session.getModelId());
            }
            // Explicit provider selection pauses auto-routing so user's chosen provider is used
            autoRouteCheck.setSelected(false);
            config.setAutoRoutingEnabled(false);

            ConfigManager.getInstance().saveConfig(config);
            AgentManager.getInstance().updateActiveService(config);
        }
    }

    private void onModelChanged() {
        if (updatingSessionUi) return;
        String model = (String) modelCombo.getSelectedItem();
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session != null && model != null) {
            session.setModelId(model);

            // Update max context limit
            IdeConfig config = ConfigManager.getInstance().getConfig();
            config.setActiveModelId(model);
            ConfigManager.getInstance().saveConfig(config);

            ProviderConfig prov = config.getProvider(session.getProviderId());
            if (prov != null) {
                ModelDefinition md = prov.findModel(model);
                if (md != null) {
                    session.getTokenTracker().setMaxContextTokens(md.getMaxContextTokens());
                }
            }
            updateTokenDisplay();
        }
    }

    private void populateModelsForProvider(String providerId) {
        updatingSessionUi = true;
        try {
            modelCombo.removeAllItems();
            IdeConfig config = ConfigManager.getInstance().getConfig();
            ProviderConfig prov = config.getProvider(providerId);
            if (prov != null && prov.getModels() != null) {
                for (ModelDefinition m : prov.getModels()) {
                    modelCombo.addItem(m.getId());
                }
                modelCombo.setSelectedItem(prov.getDefaultModel());
                AgentSession session = SessionManager.getInstance().getActiveSession();
                if (session != null) {
                    session.setModelId(prov.getDefaultModel());
                    ModelDefinition md = prov.findModel(prov.getDefaultModel());
                    if (md != null) {
                        session.getTokenTracker().setMaxContextTokens(md.getMaxContextTokens());
                    }
                }
            }
            updateTokenDisplay();
        } finally {
            updatingSessionUi = false;
        }
    }

    private void promptNewSession() {
        JTextField nameField = new JTextField("Specialist Agent " + (SessionManager.getInstance().getSessions().size() + 1), 18);
        JComboBox<String> provBox = new JComboBox<>(ConfigManager.getInstance().getConfig().getProviders().keySet().toArray(new String[0]));
        JComboBox<String> modBox = new JComboBox<>();

        Runnable updateMods = () -> {
            modBox.removeAllItems();
            String p = (String) provBox.getSelectedItem();
            ProviderConfig pc = ConfigManager.getInstance().getConfig().getProvider(p);
            if (pc != null) {
                for (ModelDefinition m : pc.getModels()) modBox.addItem(m.getId());
            }
        };
        provBox.addActionListener(e -> updateMods.run());
        updateMods.run();

        JPanel panel = new JPanel(new GridLayout(3, 2, 6, 6));
        panel.add(new JLabel("Agent Session Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Provider:"));
        panel.add(provBox);
        panel.add(new JLabel("Model:"));
        panel.add(modBox);

        int res = JOptionPane.showConfirmDialog(this, panel, "Launch New Agent Session", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION && !nameField.getText().isBlank()) {
            String name = nameField.getText().trim();
            String p = (String) provBox.getSelectedItem();
            String m = (String) modBox.getSelectedItem();
            SessionManager.getInstance().createSession(name, p, m != null ? m : "default");
            refreshSessionUi();
            reloadChatFromSession();
        }
    }

    private void promptRenameSession() {
        AgentSession current = SessionManager.getInstance().getActiveSession();
        if (current == null) return;
        String newName = JOptionPane.showInputDialog(this, "Enter new session name:", current.getName());
        if (newName != null && !newName.isBlank()) {
            SessionManager.getInstance().renameSession(current.getId(), newName.trim());
            refreshSessionUi();
        }
    }

    private void closeActiveSession() {
        AgentSession current = SessionManager.getInstance().getActiveSession();
        if (current == null) return;
        SessionManager.getInstance().closeSession(current.getId());
        refreshSessionUi();
        reloadChatFromSession();
    }

    private void triggerManualCompression() {
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session == null) return;

        ContextCompressor.CompressionResult res = ContextCompressor.compressIfExceeded(session, 0.0);
        if (res.compressed()) {
            appendSystemBubble("⚡ **Manual Context Compression Applied:**\n" + res.summary());
            updateTokenDisplay();
            reloadChatFromSession();
        } else {
            JOptionPane.showMessageDialog(this, res.summary(), "Context Compression", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public void refreshSessionUi() {
        updatingSessionUi = true;
        try {
            sessionSelectorCombo.removeAllItems();
            List<AgentSession> sessions = SessionManager.getInstance().getSessions();
            AgentSession active = SessionManager.getInstance().getActiveSession();
            int selectedIdx = 0;
            for (int i = 0; i < sessions.size(); i++) {
                AgentSession s = sessions.get(i);
                sessionSelectorCombo.addItem(s.getName() + " (" + s.getProviderId() + ")");
                if (active != null && s.getId().equals(active.getId())) {
                    selectedIdx = i;
                }
            }
            if (sessionSelectorCombo.getItemCount() > 0) {
                sessionSelectorCombo.setSelectedIndex(selectedIdx);
            }
            syncControlsToSession(active);
            updateTokenDisplay();
        } finally {
            updatingSessionUi = false;
        }
    }

    private void syncControlsToSession(AgentSession session) {
        if (session == null) return;

        IdeConfig config = ConfigManager.getInstance().getConfig();
        if (config.getProvider(session.getProviderId()) == null && !config.getProviders().isEmpty()) {
            String fallbackId = config.getProviders().containsKey(config.getActiveProviderId())
                    ? config.getActiveProviderId()
                    : config.getProviders().keySet().iterator().next();
            session.setProviderId(fallbackId);
        }

        providerCombo.removeAllItems();
        for (String pId : config.getProviders().keySet()) {
            providerCombo.addItem(pId);
        }
        providerCombo.setSelectedItem(session.getProviderId());

        modelCombo.removeAllItems();
        ProviderConfig prov = config.getProvider(session.getProviderId());
        if (prov != null) {
            for (ModelDefinition m : prov.getModels()) {
                modelCombo.addItem(m.getId());
            }
            if (prov.findModel(session.getModelId()) == null && prov.getDefaultModel() != null) {
                session.setModelId(prov.getDefaultModel());
            }
            modelCombo.setSelectedItem(session.getModelId());
        }
        autoRouteCheck.setSelected(config.isAutoRoutingEnabled());

        config.setActiveProviderId(session.getProviderId());
        if (session.getModelId() != null) {
            config.setActiveModelId(session.getModelId());
        }
        AgentManager.getInstance().updateActiveService(config);
    }

    private void updateTokenDisplay() {
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session == null) return;

        TokenTracker tracker = session.getTokenTracker();
        double pct = tracker.getUsagePercentage();
        int roundedPct = (int) Math.round(pct);

        tokenUsageLabel.setText(String.format("Tokens: %,d / %,d (%.1f%%)",
                tracker.getTotalTokens(), tracker.getMaxContextTokens(), pct));

        tokenProgressBar.setValue(Math.min(100, roundedPct));
        tokenProgressBar.setString(String.format("%.1f%%", pct));

        // Color coding: Green -> Yellow -> Orange -> Red
        if (pct < 60.0) {
            tokenProgressBar.setForeground(new Color(60, 180, 75));
        } else if (pct < 85.0) {
            tokenProgressBar.setForeground(new Color(220, 190, 40));
        } else if (pct < 95.0) {
            tokenProgressBar.setForeground(new Color(240, 130, 40));
        } else {
            tokenProgressBar.setForeground(UIUtils.ERROR_COLOR);
        }
    }

    public void reloadChatFromSession() {
        chatBox.removeAll();
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session == null || session.getMessages().isEmpty()) {
            addWelcomeMessage();
        } else {
            for (AgentMessage msg : session.getMessages()) {
                if (msg.isUser()) {
                    appendUserBubble(msg.getContent());
                } else if (msg.isAssistant()) {
                    appendAssistantBubble(msg.getContent());
                } else if (msg.getRole() == AgentRole.TOOL) {
                    appendToolBubble(msg.getToolName() != null ? msg.getToolName() : "Tool", null, msg.getContent());
                } else if (msg.getRole() == AgentRole.SYSTEM) {
                    appendSystemBubble(msg.getContent());
                }
            }
        }
        updateTokenDisplay();
        chatBox.revalidate();
        chatBox.repaint();
        scrollToBottom();
    }

    private JButton createChip(String text, String promptText) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        btn.addActionListener(e -> {
            if ("🧠 View Memory".equals(text)) {
                terminalPanel.selectMemoryTab();
                appendAssistantBubble(MemoryManager.getInstance().getMemoryStore().getRelevantContext(""));
            } else {
                inputArea.setText(promptText);
                submitPrompt();
            }
        });
        return btn;
    }

    private void addWelcomeMessage() {
        appendAssistantBubble("""
            👋 **Welcome to Axiomate AI Agent IDE!**
            Autonomous pair programming environment with **Multi-Provider Models**, **Task-Based Routing**, and **Agentic Memory**.
            
            Key Features:
            - 🌐 **Anthropic, OpenAI & Gemini URLs**: Independently configurable endpoints and custom models.
            - 🎯 **Autonomous Task Routing**: Automatically routes Refactoring to Claude, Explanations to Gemini, Tests to OpenAI.
            - 👥 **Multi-Agent Sessions**: Launch and switch between multiple concurrent agent sessions.
            - 📊 **Token Usage & Limit Meter**: Displays real-time context consumption and % limit.
            - ⚡ **95% Context Compression**: Automatically condenses conversation history into episodic memory when reaching 95% capacity.
            - 📎 **`@` File Mentions**: Type `@` to select and inject workspace files directly into the AI agent prompt.
            """);
    }

    public void clearChat() {
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session != null) {
            session.clearMessages();
        }
        chatBox.removeAll();
        chatBox.revalidate();
        chatBox.repaint();
        addWelcomeMessage();
        updateTokenDisplay();
    }

    public void sendPromptDirectly(String prompt) {
        inputArea.setText(prompt);
        submitPrompt();
    }

    private void submitPrompt() {
        String prompt = inputArea.getText().trim();
        if (prompt.isEmpty()) return;

        AIAgentService agentService = AgentManager.getInstance().getActiveService();
        if (agentService.isBusy()) {
            JOptionPane.showMessageDialog(this, "Agent is currently processing a task. Please wait or click Stop.", "Busy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        inputArea.setText("");
        appendUserBubble(prompt);

        String contextCode = includeContextCheck.isSelected() ? activeCodeSupplier.get() : "";
        if (contextCode == null) contextCode = "";

        // Auto-inject '@' file mentions from prompt
        IdeConfig config = ConfigManager.getInstance().getConfig();
        if (config.isFileMentionsEnabled()) {
            File projectDir = ProjectManager.getInstance().getCurrentProjectDirectory();
            String mentionsContext = FileMentionController.buildMentionedFilesContext(prompt, projectDir);
            if (!mentionsContext.isBlank()) {
                contextCode = contextCode.isBlank() ? mentionsContext : contextCode + "\n\n" + mentionsContext;
            }
        }

        File activeFile = ProjectManager.getInstance().getActiveFile();
        String activeFilePath = (activeFile != null) ? activeFile.getName() : "";

        setAgentState("Thinking...", UIUtils.WARNING_COLOR, true);

        currentStreamingBuffer = new StringBuilder();
        currentAssistantMessagePanel = null;
        currentAssistantTextArea = null;

        agentService.sendMessage(prompt, contextCode, activeFilePath, new AgentListener() {
            @Override
            public void onToken(String token) {
                SwingUtilities.invokeLater(() -> {
                    ensureAssistantBubble();
                    currentStreamingBuffer.append(token);
                    currentAssistantTextArea.setText(currentStreamingBuffer.toString());
                    scrollToBottom();
                });
            }

            @Override
            public void onThinking(String thought) {
                SwingUtilities.invokeLater(() -> {
                    setAgentState("Thinking...", UIUtils.WARNING_COLOR, true);
                    // Show reasoning in chat AND log it to terminal
                    appendThinkingBubble(thought);
                    terminalPanel.appendAgentLog("AGENT REASONING", thought);
                });
            }

            @Override
            public void onToolCall(String toolName, String input) {
                SwingUtilities.invokeLater(() -> {
                    setAgentState("Running tool: " + toolName, UIUtils.ACCENT_COLOR, true);
                    appendToolBubble(toolName, input, null);
                    terminalPanel.appendAgentLog("TOOL INVOCATION: " + toolName, input);
                });
            }

            @Override
            public void onToolResult(String toolName, String output) {
                SwingUtilities.invokeLater(() -> {
                    appendToolBubble(toolName, null, output);
                    terminalPanel.appendAgentLog("TOOL RESULT: " + toolName, output);
                });
            }

            @Override
            public void onComplete(String fullResponse) {
                SwingUtilities.invokeLater(() -> {
                    // If onToken() was never called (e.g. empty response), ensure bubble is closed
                    if (currentAssistantMessagePanel != null && currentAssistantTextArea != null) {
                        currentAssistantMessagePanel = null;
                        currentAssistantTextArea = null;
                    }
                    setAgentState("Ready", UIUtils.SUCCESS_COLOR, false);
                    updateTokenDisplay();
                    scrollToBottom();
                });
            }

            @Override
            public void onError(Throwable throwable) {
                SwingUtilities.invokeLater(() -> {
                    setAgentState("Error", UIUtils.ERROR_COLOR, false);
                    appendAssistantBubble("⚠️ **Error occurred:** " + throwable.getMessage());
                    terminalPanel.appendAgentLog("ERROR", throwable.toString());
                    updateTokenDisplay();
                });
            }
        });
    }

    private void cancelAgent() {
        AgentManager.getInstance().getActiveService().cancelCurrentTask();
        setAgentState("Ready", UIUtils.SUCCESS_COLOR, false);
    }

    private void setAgentState(String status, Color color, boolean isBusy) {
        statusBadge.setText("● " + status);
        statusBadge.setForeground(color);
        sendBtn.setEnabled(!isBusy);
        stopBtn.setEnabled(isBusy);
    }

    private void appendUserBubble(String text) {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBorder(new EmptyBorder(6, 6, 6, 6));

        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(37, 50, 75));
        inner.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel header = new JLabel("You");
        header.setFont(new Font("SansSerif", Font.BOLD, 11));
        header.setForeground(UIUtils.ACCENT_COLOR);

        JTextArea area = createBubbleTextArea(text);
        inner.add(header, BorderLayout.NORTH);
        inner.add(area, BorderLayout.CENTER);

        bubble.add(inner, BorderLayout.CENTER);
        chatBox.add(bubble);
        chatBox.add(Box.createVerticalStrut(6));
        scrollToBottom();
    }

    private void ensureAssistantBubble() {
        if (currentAssistantMessagePanel == null) {
            JPanel bubble = new JPanel(new BorderLayout());
            bubble.setBorder(new EmptyBorder(6, 6, 6, 6));

            JPanel inner = new JPanel(new BorderLayout());
            inner.setBackground(new Color(36, 38, 44));
            inner.setBorder(new EmptyBorder(8, 12, 8, 12));

            AgentSession session = SessionManager.getInstance().getActiveSession();
            String title = (session != null)
                    ? session.getName() + " [" + session.getModelId() + "]"
                    : "Axiomate AI";

            JLabel header = new JLabel(title, UIUtils.createSparkleIcon(14, UIUtils.ACCENT_PURPLE), JLabel.LEFT);
            header.setFont(new Font("SansSerif", Font.BOLD, 11));
            header.setForeground(UIUtils.ACCENT_PURPLE);

            currentAssistantTextArea = createBubbleTextArea("");
            inner.add(header, BorderLayout.NORTH);
            inner.add(currentAssistantTextArea, BorderLayout.CENTER);

            bubble.add(inner, BorderLayout.CENTER);
            chatBox.add(bubble);
            chatBox.add(Box.createVerticalStrut(6));

            currentAssistantMessagePanel = bubble;
        }
    }

    /**
     * Renders the model's thinking/reasoning in a styled dark-purple bubble in the chat.
     * The bubble is clearly distinguished from the final answer with a "🧠 Reasoning" header.
     */
    private void appendThinkingBubble(String thought) {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBorder(new EmptyBorder(4, 12, 4, 12));

        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(32, 28, 50));
        inner.setBorder(new LineBorder(new Color(110, 80, 180), 1));

        JLabel header = new JLabel("🧠  Model Reasoning  (thinking block)");
        header.setFont(new Font("SansSerif", Font.BOLD, 10));
        header.setForeground(new Color(170, 130, 255));
        header.setBorder(new EmptyBorder(5, 8, 3, 8));

        // Trim long thinking to a preview — full content visible in terminal log
        String display = thought;
        if (display.length() > 800) {
            display = display.substring(0, 800) + "\n… (see terminal log for full reasoning)";
        }
        JTextArea area = createBubbleTextArea(display);
        area.setFont(new Font("SansSerif", Font.ITALIC, 12));
        area.setForeground(new Color(190, 170, 230));
        area.setBorder(new EmptyBorder(2, 8, 6, 8));

        inner.add(header, BorderLayout.NORTH);
        inner.add(area, BorderLayout.CENTER);

        bubble.add(inner, BorderLayout.CENTER);
        chatBox.add(bubble);
        chatBox.add(Box.createVerticalStrut(4));
        scrollToBottom();
    }

    private void appendAssistantBubble(String text) {
        ensureAssistantBubble();
        currentAssistantTextArea.setText(text);
        currentAssistantMessagePanel = null;
        currentAssistantTextArea = null;
        scrollToBottom();
    }

    private void appendSystemBubble(String text) {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBorder(new EmptyBorder(4, 12, 4, 12));

        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(45, 38, 55));
        inner.setBorder(new LineBorder(UIUtils.ACCENT_PURPLE, 1));

        JLabel header = new JLabel("⚡ System Notification / Context Compression");
        header.setFont(new Font("SansSerif", Font.BOLD, 10));
        header.setForeground(new Color(220, 180, 255));
        header.setBorder(new EmptyBorder(4, 8, 2, 8));

        JTextArea area = createBubbleTextArea(text);
        area.setForeground(new Color(230, 220, 245));
        area.setBorder(new EmptyBorder(2, 8, 6, 8));

        inner.add(header, BorderLayout.NORTH);
        inner.add(area, BorderLayout.CENTER);

        bubble.add(inner, BorderLayout.CENTER);
        chatBox.add(bubble);
        chatBox.add(Box.createVerticalStrut(4));
        scrollToBottom();
    }

    private void appendToolBubble(String toolName, String input, String output) {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBorder(new EmptyBorder(4, 12, 4, 12));

        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(28, 30, 34));
        inner.setBorder(new LineBorder(new Color(70, 70, 80), 1));

        String title = (input != null) ? "Tool Call: " + toolName : "Tool Result: " + toolName;
        JLabel header = new JLabel(title);
        header.setFont(new Font("Monospaced", Font.BOLD, 10));
        header.setForeground(Color.LIGHT_GRAY);
        header.setBorder(new EmptyBorder(4, 6, 4, 6));

        JTextArea area = createBubbleTextArea(input != null ? input : output);
        area.setFont(new Font("Consolas", Font.PLAIN, 11));
        area.setForeground(new Color(180, 220, 180));
        area.setBorder(new EmptyBorder(2, 6, 6, 6));

        inner.add(header, BorderLayout.NORTH);
        inner.add(area, BorderLayout.CENTER);

        bubble.add(inner, BorderLayout.CENTER);
        chatBox.add(bubble);
        chatBox.add(Box.createVerticalStrut(4));
        scrollToBottom();
    }

    private JTextArea createBubbleTextArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(new Font("SansSerif", Font.PLAIN, 13));
        area.setForeground(new Color(230, 230, 230));
        return area;
    }

    private void scrollToBottom() {
        chatBox.revalidate();
        chatBox.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }
}


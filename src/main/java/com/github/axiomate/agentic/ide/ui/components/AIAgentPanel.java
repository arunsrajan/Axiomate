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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
    private final JButton loadSessionBtn;

    // Agent Output Display Show/Hide & Collapsible Controls
    private final JCheckBox showToolCallsCheck;
    private final JCheckBox showToolResultsCheck;
    private final JCheckBox showThinkingCheck;
    private final JCheckBox showWalkthroughCheck;
    private final JButton collapseAllBtn;
    private final JButton expandAllBtn;
    private final JButton toggleCategoriesBtn;

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
        sessionSelectorCombo.setPreferredSize(new Dimension(170, 24));
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

        loadSessionBtn = new JButton("📂 Sessions ▾");
        loadSessionBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        loadSessionBtn.setToolTipText("Load, reload, or export/import project agent sessions");
        loadSessionBtn.addActionListener(e -> promptLoadOrImportSessions());
        sessionLeft.add(loadSessionBtn);

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

        autoRouteCheck = new JCheckBox("Auto-Route", false);
        autoRouteCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        autoRouteCheck.setToolTipText("Automatically chooses provider & model per task for this session");
        autoRouteCheck.addActionListener(e -> {
            AgentSession session = SessionManager.getInstance().getActiveSession();
            if (session != null) {
                session.setAutoRoutingEnabled(autoRouteCheck.isSelected());
                SessionManager.getInstance().autoSaveCurrentProjectSessions();
                refreshSessionUi();
            }
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

        // 5. Output Display Filtering & Visibility Bar
        JPanel outputDisplayBar = new JPanel(new BorderLayout(4, 0));
        outputDisplayBar.setBorder(new CompoundBorder(new LineBorder(new Color(45, 48, 56), 1), new EmptyBorder(2, 6, 2, 6)));
        outputDisplayBar.setBackground(new Color(24, 26, 32));

        JPanel filtersLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        filtersLeft.setOpaque(false);

        JLabel filterLabel = new JLabel("Output:");
        filterLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
        filterLabel.setForeground(new Color(160, 165, 180));
        filtersLeft.add(filterLabel);

        showToolCallsCheck = new JCheckBox("🔧 Requests", true);
        showToolCallsCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        showToolCallsCheck.setForeground(new Color(88, 166, 255));
        showToolCallsCheck.setToolTipText("Show or hide tool calling requests and input arguments");
        showToolCallsCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showToolCallsCheck);

        showToolResultsCheck = new JCheckBox("📥 Responses", true);
        showToolResultsCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        showToolResultsCheck.setForeground(new Color(126, 231, 135));
        showToolResultsCheck.setToolTipText("Show or hide tool execution results and stdout");
        showToolResultsCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showToolResultsCheck);

        showThinkingCheck = new JCheckBox("🧠 Reasoning", true);
        showThinkingCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        showThinkingCheck.setForeground(new Color(210, 168, 255));
        showThinkingCheck.setToolTipText("Show or hide model thinking and reasoning blocks");
        showThinkingCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showThinkingCheck);

        showWalkthroughCheck = new JCheckBox("📝 Walkthrough", true);
        showWalkthroughCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        showWalkthroughCheck.setForeground(new Color(240, 136, 62));
        showWalkthroughCheck.setToolTipText("Show or hide assistant final walkthrough and answers");
        showWalkthroughCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showWalkthroughCheck);

        attachCategoryContextMenu(showToolCallsCheck, MessageDisplayType.TOOL_REQUEST, "Requests");
        attachCategoryContextMenu(showToolResultsCheck, MessageDisplayType.TOOL_RESPONSE, "Responses");
        attachCategoryContextMenu(showThinkingCheck, MessageDisplayType.THINKING, "Reasoning");
        attachCategoryContextMenu(showWalkthroughCheck, MessageDisplayType.WALKTHROUGH, "Walkthroughs");

        JPanel actionsRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        actionsRight.setOpaque(false);

        collapseAllBtn = new JButton("▴ Collapse All");
        collapseAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        collapseAllBtn.setToolTipText("Collapse all collapsible bubbles");
        collapseAllBtn.addActionListener(e -> collapseAllCards());
        actionsRight.add(collapseAllBtn);

        expandAllBtn = new JButton("▾ Expand All");
        expandAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        expandAllBtn.setToolTipText("Expand all collapsible bubbles");
        expandAllBtn.addActionListener(e -> expandAllCards());
        actionsRight.add(expandAllBtn);

        toggleCategoriesBtn = new JButton("▾ Categories");
        toggleCategoriesBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        toggleCategoriesBtn.setToolTipText("Expand or collapse specific output categories");
        toggleCategoriesBtn.addActionListener(e -> showCategoriesToggleMenu(toggleCategoriesBtn));
        actionsRight.add(toggleCategoriesBtn);

        outputDisplayBar.add(filtersLeft, BorderLayout.CENTER);
        outputDisplayBar.add(actionsRight, BorderLayout.EAST);

        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.add(headerPanel);
        topContainer.add(sessionBar);
        topContainer.add(controlBar);
        topContainer.add(chipsPanel);
        topContainer.add(outputDisplayBar);
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

            // Explicit provider selection pauses auto-routing for this session
            session.setAutoRoutingEnabled(false);
            autoRouteCheck.setSelected(false);
            SessionManager.getInstance().autoSaveCurrentProjectSessions();

            // Synchronize runtime active provider for immediate execution
            IdeConfig config = ConfigManager.getInstance().getConfig();
            config.setActiveProviderId(provider);
            if (session.getModelId() != null) {
                config.setActiveModelId(session.getModelId());
            }
            AgentManager.getInstance().updateActiveService(config);
        }
    }

    private void onModelChanged() {
        if (updatingSessionUi) return;
        String model = (String) modelCombo.getSelectedItem();
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session != null && model != null) {
            session.setModelId(model);
            SessionManager.getInstance().autoSaveCurrentProjectSessions();

            IdeConfig config = ConfigManager.getInstance().getConfig();
            config.setActiveModelId(model);

            ProviderConfig prov = config.getProvider(session.getProviderId());
            if (prov != null) {
                ModelDefinition md = prov.findModel(model);
                if (md != null) {
                    session.getTokenTracker().setMaxContextTokens(md.getMaxContextTokens());
                }
            }
            updateTokenDisplay();
            AgentManager.getInstance().updateActiveService(config);
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
        JCheckBox autoRouteNewCheck = new JCheckBox("Enable Task-Based Auto-Routing", false);

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

        JPanel panel = new JPanel(new GridLayout(4, 2, 6, 6));
        panel.add(new JLabel("Agent Session Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Provider:"));
        panel.add(provBox);
        panel.add(new JLabel("Model:"));
        panel.add(modBox);
        panel.add(new JLabel("Auto-Routing:"));
        panel.add(autoRouteNewCheck);

        int res = JOptionPane.showConfirmDialog(this, panel, "Launch New Agent Session", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION && !nameField.getText().isBlank()) {
            String name = nameField.getText().trim();
            String p = (String) provBox.getSelectedItem();
            String m = (String) modBox.getSelectedItem();
            SessionManager.getInstance().createSession(name, p, m != null ? m : "default", autoRouteNewCheck.isSelected());
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
                String routeTag = s.isAutoRoutingEnabled() ? ", auto" : "";
                sessionSelectorCombo.addItem(s.getName() + " (" + s.getProviderId() + routeTag + ")");
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
        autoRouteCheck.setSelected(session.isAutoRoutingEnabled());

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
                } else if (msg.getRole() == AgentRole.TOOL_CALL) {
                    appendToolRequestBubble(msg.getToolName() != null ? msg.getToolName() : "Tool", msg.getContent());
                } else if (msg.getRole() == AgentRole.TOOL) {
                    appendToolResultBubble(msg.getToolName() != null ? msg.getToolName() : "Tool", msg.getContent());
                } else if (msg.getRole() == AgentRole.THINKING) {
                    appendThinkingBubble(msg.getContent());
                } else if (msg.getRole() == AgentRole.SYSTEM) {
                    appendSystemBubble(msg.getContent());
                }
            }
        }
        applyDisplayFilters();
        updateTokenDisplay();
        chatBox.revalidate();
        chatBox.repaint();
        scrollToBottom();
    }

    public void applyDisplayFilters() {
        boolean showToolCalls = showToolCallsCheck.isSelected();
        boolean showToolResults = showToolResultsCheck.isSelected();
        boolean showThinking = showThinkingCheck.isSelected();
        boolean showWalkthrough = showWalkthroughCheck.isSelected();

        for (Component comp : chatBox.getComponents()) {
            if (comp instanceof MessageCard card) {
                switch (card.getDisplayType()) {
                    case TOOL_REQUEST -> card.setVisible(showToolCalls);
                    case TOOL_RESPONSE -> card.setVisible(showToolResults);
                    case THINKING -> card.setVisible(showThinking);
                    case WALKTHROUGH -> card.setVisible(showWalkthrough);
                    case USER, SYSTEM -> card.setVisible(true);
                }
            }
        }
        chatBox.revalidate();
        chatBox.repaint();
    }

    public void collapseAllCards() {
        for (Component comp : chatBox.getComponents()) {
            if (comp instanceof MessageCard card) {
                card.setCollapsed(true);
            }
        }
        chatBox.revalidate();
        chatBox.repaint();
    }

    public void expandAllCards() {
        for (Component comp : chatBox.getComponents()) {
            if (comp instanceof MessageCard card) {
                card.setCollapsed(false);
            }
        }
        chatBox.revalidate();
        chatBox.repaint();
    }

    public void setCategoryCollapsed(MessageDisplayType type, boolean collapsed) {
        for (Component comp : chatBox.getComponents()) {
            if (comp instanceof MessageCard card) {
                if (card.getDisplayType() == type) {
                    card.setCollapsed(collapsed);
                }
            }
        }
        chatBox.revalidate();
        chatBox.repaint();
    }

    private void attachCategoryContextMenu(JCheckBox check, MessageDisplayType type, String categoryName) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem expItem = new JMenuItem("▾ Expand All " + categoryName);
        expItem.addActionListener(e -> setCategoryCollapsed(type, false));
        JMenuItem colItem = new JMenuItem("▴ Collapse All " + categoryName);
        colItem.addActionListener(e -> setCategoryCollapsed(type, true));
        menu.add(expItem);
        menu.add(colItem);
        check.setComponentPopupMenu(menu);
    }

    private void showCategoriesToggleMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem expAll = new JMenuItem("▾ Expand All Outputs");
        expAll.addActionListener(e -> expandAllCards());
        JMenuItem colAll = new JMenuItem("▴ Collapse All Outputs");
        colAll.addActionListener(e -> collapseAllCards());
        menu.add(expAll);
        menu.add(colAll);
        menu.addSeparator();

        JMenuItem expReq = new JMenuItem("🔧 Expand All Requests");
        expReq.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.TOOL_REQUEST, false));
        JMenuItem colReq = new JMenuItem("🔧 Collapse All Requests");
        colReq.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.TOOL_REQUEST, true));
        menu.add(expReq);
        menu.add(colReq);
        menu.addSeparator();

        JMenuItem expRes = new JMenuItem("📥 Expand All Responses");
        expRes.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.TOOL_RESPONSE, false));
        JMenuItem colRes = new JMenuItem("📥 Collapse All Responses");
        colRes.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.TOOL_RESPONSE, true));
        menu.add(expRes);
        menu.add(colRes);
        menu.addSeparator();

        JMenuItem expThk = new JMenuItem("🧠 Expand All Reasoning");
        expThk.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.THINKING, false));
        JMenuItem colThk = new JMenuItem("🧠 Collapse All Reasoning");
        colThk.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.THINKING, true));
        menu.add(expThk);
        menu.add(colThk);
        menu.addSeparator();

        JMenuItem expWlk = new JMenuItem("📝 Expand All Walkthroughs");
        expWlk.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.WALKTHROUGH, false));
        JMenuItem colWlk = new JMenuItem("📝 Collapse All Walkthroughs");
        colWlk.addActionListener(e -> setCategoryCollapsed(MessageDisplayType.WALKTHROUGH, true));
        menu.add(expWlk);
        menu.add(colWlk);

        menu.show(invoker, 0, invoker.getHeight());
    }

    public void saveProjectSessions() {
        File projDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (projDir != null) {
            SessionManager.getInstance().saveSessionsForProject(projDir);
            JOptionPane.showMessageDialog(this,
                    "Saved " + SessionManager.getInstance().getSessions().size() + " session(s) for project [" + projDir.getName() + "]",
                    "Sessions Saved", JOptionPane.INFORMATION_MESSAGE);
        } else {
            SessionManager.getInstance().autoSaveCurrentProjectSessions();
            JOptionPane.showMessageDialog(this, "Saved active sessions!", "Sessions Saved", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public void promptLoadOrImportSessions() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem reloadItem = new JMenuItem("🔄 Reload Sessions from Project State");
        reloadItem.addActionListener(e -> {
            File projDir = ProjectManager.getInstance().getCurrentProjectDirectory();
            if (projDir != null) {
                SessionManager.getInstance().loadSessionsForProject(projDir);
                reloadChatFromSession();
            } else {
                JOptionPane.showMessageDialog(this, "No active project directory.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        JMenuItem exportItem = new JMenuItem("📤 Export Sessions to JSON File...");
        exportItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            File projDir = ProjectManager.getInstance().getCurrentProjectDirectory();
            if (projDir != null) {
                chooser.setCurrentDirectory(projDir);
                chooser.setSelectedFile(new File(projDir, "agent-sessions.json"));
            } else {
                chooser.setSelectedFile(new File("agent-sessions.json"));
            }
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    SessionManager.getInstance().exportSessionsToFile(chooser.getSelectedFile());
                    JOptionPane.showMessageDialog(this,
                            "Exported sessions to:\n" + chooser.getSelectedFile().getAbsolutePath(),
                            "Export Successful", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Failed to export sessions: " + ex.getMessage(),
                            "Export Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JMenuItem importItem = new JMenuItem("📥 Import Sessions from JSON File...");
        importItem.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            File projDir = ProjectManager.getInstance().getCurrentProjectDirectory();
            if (projDir != null) chooser.setCurrentDirectory(projDir);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                int opt = JOptionPane.showConfirmDialog(this,
                        "Do you want to append these sessions to current sessions? (Choose 'No' to replace)",
                        "Import Mode", JOptionPane.YES_NO_CANCEL_OPTION);
                if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) return;
                boolean append = (opt == JOptionPane.YES_OPTION);
                try {
                    SessionManager.getInstance().importSessionsFromFile(chooser.getSelectedFile(), append);
                    reloadChatFromSession();
                    JOptionPane.showMessageDialog(this, "Successfully imported sessions!",
                            "Import Successful", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Failed to import sessions: " + ex.getMessage(),
                            "Import Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        menu.add(reloadItem);
        menu.addSeparator();
        menu.add(exportItem);
        menu.add(importItem);
        menu.show(loadSessionBtn, 0, loadSessionBtn.getHeight());
    }

    private void recordSessionMessageIfNew(AgentMessage msg) {
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session == null || msg == null) return;
        List<AgentMessage> list = session.getMessages();
        if (!list.isEmpty()) {
            AgentMessage last = list.get(list.size() - 1);
            if (last.getRole() == msg.getRole() &&
                last.getContent().equals(msg.getContent()) &&
                java.util.Objects.equals(last.getToolName(), msg.getToolName())) {
                return;
            }
        }
        session.addMessage(msg);
        SessionManager.getInstance().autoSaveCurrentProjectSessions();
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
            - 👥 **Multi-Agent Sessions**: Launch, save, load, and switch between multiple concurrent agent sessions per project.
            - 🎛 **Output Display Filters**: Show or hide tool requests, responses, model reasoning, and walkthroughs on demand.
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
                    recordSessionMessageIfNew(new AgentMessage(AgentRole.THINKING, thought, null));
                    appendThinkingBubble(thought);
                    terminalPanel.appendAgentLog("AGENT REASONING", thought);
                });
            }

            @Override
            public void onToolCall(String toolName, String input) {
                SwingUtilities.invokeLater(() -> {
                    setAgentState("Running tool: " + toolName, UIUtils.ACCENT_COLOR, true);
                    recordSessionMessageIfNew(new AgentMessage(AgentRole.TOOL_CALL, input, toolName));
                    appendToolRequestBubble(toolName, input);
                    terminalPanel.appendAgentLog("TOOL INVOCATION: " + toolName, input);
                });
            }

            @Override
            public void onToolResult(String toolName, String output) {
                SwingUtilities.invokeLater(() -> {
                    recordSessionMessageIfNew(new AgentMessage(AgentRole.TOOL, output, toolName));
                    appendToolResultBubble(toolName, output);
                    terminalPanel.appendAgentLog("TOOL RESULT: " + toolName, output);
                });
            }

            @Override
            public void onComplete(String fullResponse) {
                SwingUtilities.invokeLater(() -> {
                    if (currentAssistantMessagePanel != null && currentAssistantTextArea != null) {
                        currentAssistantMessagePanel = null;
                        currentAssistantTextArea = null;
                    }
                    setAgentState("Ready", UIUtils.SUCCESS_COLOR, false);
                    updateTokenDisplay();
                    SessionManager.getInstance().autoSaveCurrentProjectSessions();
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

    private JButton createCollapseToggleButton() {
        JButton btn = new JButton("▴ Collapse");
        btn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        btn.setForeground(new Color(160, 165, 180));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(1, 4, 1, 4));
        return btn;
    }

    private void appendUserBubble(String text) {
        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(37, 50, 75));
        inner.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel header = new JLabel("You");
        header.setFont(new Font("SansSerif", Font.BOLD, 11));
        header.setForeground(UIUtils.ACCENT_COLOR);

        JTextArea area = createBubbleTextArea(text);
        inner.add(header, BorderLayout.NORTH);
        inner.add(area, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.USER, inner, null);
        card.setBorder(new EmptyBorder(6, 6, 6, 6));
        card.add(inner, BorderLayout.CENTER);

        chatBox.add(card);
        chatBox.add(Box.createVerticalStrut(6));
        scrollToBottom();
    }

    private void ensureAssistantBubble() {
        if (currentAssistantMessagePanel == null) {
            JPanel inner = new JPanel(new BorderLayout());
            inner.setBackground(new Color(36, 38, 44));
            inner.setBorder(new EmptyBorder(8, 12, 8, 12));

            AgentSession session = SessionManager.getInstance().getActiveSession();
            String title = (session != null)
                    ? session.getName() + " [" + session.getModelId() + "]"
                    : "Axiomate AI";

            JPanel headerBar = new JPanel(new BorderLayout());
            headerBar.setOpaque(false);
            headerBar.setBorder(new EmptyBorder(0, 0, 4, 0));

            JLabel header = new JLabel(title + " — Walkthrough / Response", UIUtils.createSparkleIcon(14, UIUtils.ACCENT_PURPLE), JLabel.LEFT);
            header.setFont(new Font("SansSerif", Font.BOLD, 11));
            header.setForeground(UIUtils.ACCENT_PURPLE);

            JButton toggle = createCollapseToggleButton();

            headerBar.add(header, BorderLayout.WEST);
            headerBar.add(toggle, BorderLayout.EAST);

            currentAssistantTextArea = createBubbleTextArea("");

            JPanel contentPanel = new JPanel(new BorderLayout());
            contentPanel.setOpaque(false);
            contentPanel.add(currentAssistantTextArea, BorderLayout.CENTER);

            inner.add(headerBar, BorderLayout.NORTH);
            inner.add(contentPanel, BorderLayout.CENTER);

            MessageCard card = new MessageCard(MessageDisplayType.WALKTHROUGH, contentPanel, toggle);
            toggle.addActionListener(e -> card.setCollapsed(!card.isCollapsed()));
            headerBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            headerBar.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    card.setCollapsed(!card.isCollapsed());
                }
            });
            card.setBorder(new EmptyBorder(6, 6, 6, 6));
            card.add(inner, BorderLayout.CENTER);

            card.setVisible(showWalkthroughCheck.isSelected());
            chatBox.add(card);
            chatBox.add(Box.createVerticalStrut(6));

            currentAssistantMessagePanel = card;
        }
    }

    /**
     * Renders the model's thinking/reasoning in a styled dark-purple bubble in the chat.
     */
    private void appendThinkingBubble(String thought) {
        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(32, 26, 48));
        inner.setBorder(new LineBorder(new Color(110, 80, 180), 1));

        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setOpaque(false);
        headerBar.setBorder(new EmptyBorder(4, 8, 4, 8));

        JLabel header = new JLabel("🧠  Model Reasoning  (thinking block)");
        header.setFont(new Font("SansSerif", Font.BOLD, 10));
        header.setForeground(new Color(210, 168, 255));

        JButton toggle = createCollapseToggleButton();

        headerBar.add(header, BorderLayout.WEST);
        headerBar.add(toggle, BorderLayout.EAST);

        String display = thought;
        if (display.length() > 800) {
            display = display.substring(0, 800) + "\n… (see terminal log for full reasoning)";
        }
        JTextArea area = createBubbleTextArea(display);
        area.setFont(new Font("SansSerif", Font.ITALIC, 12));
        area.setForeground(new Color(215, 195, 245));
        area.setBorder(new EmptyBorder(4, 8, 6, 8));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(area, BorderLayout.CENTER);

        inner.add(headerBar, BorderLayout.NORTH);
        inner.add(contentPanel, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.THINKING, contentPanel, toggle);
        toggle.addActionListener(e -> card.setCollapsed(!card.isCollapsed()));
        headerBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                card.setCollapsed(!card.isCollapsed());
            }
        });
        card.setBorder(new EmptyBorder(4, 12, 4, 12));
        card.add(inner, BorderLayout.CENTER);

        card.setVisible(showThinkingCheck.isSelected());
        chatBox.add(card);
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

        MessageCard card = new MessageCard(MessageDisplayType.SYSTEM, inner, null);
        card.setBorder(new EmptyBorder(4, 12, 4, 12));
        card.add(inner, BorderLayout.CENTER);

        chatBox.add(card);
        chatBox.add(Box.createVerticalStrut(4));
        scrollToBottom();
    }

    private void appendToolRequestBubble(String toolName, String input) {
        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(24, 28, 38));
        inner.setBorder(new LineBorder(new Color(56, 90, 140), 1));

        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setOpaque(false);
        headerBar.setBorder(new EmptyBorder(4, 8, 4, 8));

        JLabel header = new JLabel("🔧  Tool Call: " + toolName);
        header.setFont(new Font("Monospaced", Font.BOLD, 11));
        header.setForeground(new Color(88, 166, 255));

        JButton toggle = createCollapseToggleButton();

        headerBar.add(header, BorderLayout.WEST);
        headerBar.add(toggle, BorderLayout.EAST);

        JTextArea area = createBubbleTextArea(input != null ? input : "{}");
        area.setFont(new Font("Consolas", Font.PLAIN, 11));
        area.setForeground(new Color(190, 220, 255));
        area.setBorder(new EmptyBorder(4, 8, 6, 8));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(area, BorderLayout.CENTER);

        inner.add(headerBar, BorderLayout.NORTH);
        inner.add(contentPanel, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.TOOL_REQUEST, contentPanel, toggle);
        toggle.addActionListener(e -> card.setCollapsed(!card.isCollapsed()));
        headerBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                card.setCollapsed(!card.isCollapsed());
            }
        });
        card.setBorder(new EmptyBorder(4, 12, 4, 12));
        card.add(inner, BorderLayout.CENTER);

        card.setVisible(showToolCallsCheck.isSelected());
        chatBox.add(card);
        chatBox.add(Box.createVerticalStrut(4));
        scrollToBottom();
    }

    private void appendToolResultBubble(String toolName, String output) {
        JPanel inner = new JPanel(new BorderLayout());
        inner.setBackground(new Color(22, 32, 26));
        inner.setBorder(new LineBorder(new Color(40, 100, 60), 1));

        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setOpaque(false);
        headerBar.setBorder(new EmptyBorder(4, 8, 4, 8));

        int lines = (output != null) ? output.split("\r\n|\r|\n").length : 0;
        JLabel header = new JLabel("📥  Tool Result: " + toolName + " (" + lines + " lines)");
        header.setFont(new Font("Monospaced", Font.BOLD, 11));
        header.setForeground(new Color(126, 231, 135));

        JButton toggle = createCollapseToggleButton();

        headerBar.add(header, BorderLayout.WEST);
        headerBar.add(toggle, BorderLayout.EAST);

        JTextArea area = createBubbleTextArea(output != null ? output : "(empty)");
        area.setFont(new Font("Consolas", Font.PLAIN, 11));
        area.setForeground(new Color(185, 235, 195));
        area.setBorder(new EmptyBorder(4, 8, 6, 8));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(area, BorderLayout.CENTER);

        inner.add(headerBar, BorderLayout.NORTH);
        inner.add(contentPanel, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.TOOL_RESPONSE, contentPanel, toggle);
        toggle.addActionListener(e -> card.setCollapsed(!card.isCollapsed()));
        headerBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        headerBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                card.setCollapsed(!card.isCollapsed());
            }
        });
        card.setBorder(new EmptyBorder(4, 12, 4, 12));
        card.add(inner, BorderLayout.CENTER);

        card.setVisible(showToolResultsCheck.isSelected());
        chatBox.add(card);
        chatBox.add(Box.createVerticalStrut(4));
        scrollToBottom();
    }

    private void appendToolBubble(String toolName, String input, String output) {
        if (input != null) {
            appendToolRequestBubble(toolName, input);
        }
        if (output != null) {
            appendToolResultBubble(toolName, output);
        }
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

    public enum MessageDisplayType {
        USER,
        TOOL_REQUEST,
        TOOL_RESPONSE,
        THINKING,
        WALKTHROUGH,
        SYSTEM
    }

    public static class MessageCard extends JPanel {
        private final MessageDisplayType displayType;
        private final JComponent contentComponent;
        private final JButton toggleBtn;
        private boolean collapsed = false;

        public MessageCard(MessageDisplayType displayType, JComponent contentComponent, JButton toggleBtn) {
            super(new BorderLayout());
            this.displayType = displayType;
            this.contentComponent = contentComponent;
            this.toggleBtn = toggleBtn;
            setOpaque(false);
        }

        public MessageDisplayType getDisplayType() {
            return displayType;
        }

        public boolean isCollapsed() {
            return collapsed;
        }

        public void setCollapsed(boolean collapsed) {
            this.collapsed = collapsed;
            if (contentComponent != null) {
                contentComponent.setVisible(!collapsed);
            }
            if (toggleBtn != null) {
                toggleBtn.setText(collapsed ? "▾ Expand" : "▴ Collapse");
            }
            revalidate();
            repaint();
            Container parent = getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        }

        public void toggleCollapsed() {
            setCollapsed(!collapsed);
        }

        public JButton getToggleBtn() {
            return toggleBtn;
        }

        public JComponent getContentComponent() {
            return contentComponent;
        }
    }
}



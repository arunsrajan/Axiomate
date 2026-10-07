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
import com.github.axiomate.agentic.ide.agent.vision.ImageAttachment;
import com.github.axiomate.agentic.ide.agent.vision.VisionSupport;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.Dispatch;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.ui.util.WrapLayout;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
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
    private final ThinkingIndicator thinkingIndicator = new ThinkingIndicator();
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

    private final SlashCommandCompletion slashCompletion;
    private final List<JButton> quickActions = new ArrayList<>();
    private final ImageAttachmentStrip attachmentStrip = new ImageAttachmentStrip(true, this::updateVisionHint);
    private final JLabel visionHint = new JLabel();
    private final List<Runnable> themeAppliers = new ArrayList<>();
    private IdeActions ideActions = IdeActions.NONE;
    private String displayedSessionId;

    private JPanel currentAssistantMessagePanel;
    private JTextArea currentAssistantTextArea;
    private StringBuilder currentStreamingBuffer;
    /** Reasoning bubble being filled while the model streams its thinking. */
    private ThinkingView liveThinking;
    private StringBuilder liveThinkingBuffer;
    private boolean updatingSessionUi = false;

    public AIAgentPanel(Supplier<String> activeCodeSupplier, TerminalPanel terminalPanel) {
        this.activeCodeSupplier = activeCodeSupplier;
        this.terminalPanel = terminalPanel;
        setLayout(new BorderLayout());
        onTheme(() -> setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, UIUtils.borderColor())));

        // 1. Main Header Panel
        JPanel headerPanel = new JPanel(new BorderLayout(8, 0));
        headerPanel.setBorder(new EmptyBorder(8, 12, 6, 12));
        onTheme(() -> headerPanel.setBackground(UIUtils.surface(2)));

        JPanel titleSubPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleSubPanel.setOpaque(false);
        JLabel iconLabel = new JLabel();
        onTheme(() -> iconLabel.setIcon(UIUtils.glyph(UIUtils.Glyph.SPARK, 16, UIUtils.ACCENT_COLOR)));
        JLabel titleLabel = new JLabel("Axiomate");
        titleLabel.setFont(UIUtils.uiFont(Font.BOLD, 13f));
        onTheme(() -> titleLabel.setForeground(UIUtils.foreground()));

        statusBadge = new JLabel("Ready");
        statusBadge.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        onTheme(() -> statusBadge.setForeground(UIUtils.mutedForeground()));

        titleSubPanel.add(iconLabel);
        titleSubPanel.add(titleLabel);
        titleSubPanel.add(Box.createHorizontalStrut(8));

        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headerButtons.setOpaque(false);

        JButton newChatBtn = UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.PLUS, 16, null), "New agent session", e -> promptNewSession());
        JButton moreBtn = UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.MORE, 16, null), "More actions", null);
        moreBtn.addActionListener(e -> buildOverflowMenu().show(moreBtn, 0, moreBtn.getHeight()));
        headerButtons.add(newChatBtn);
        headerButtons.add(moreBtn);

        headerPanel.add(titleSubPanel, BorderLayout.WEST);
        headerPanel.add(headerButtons, BorderLayout.EAST);

        // 2. Session Management Bar
        JPanel sessionBar = new JPanel(new BorderLayout(6, 0));
        sessionBar.setBorder(new EmptyBorder(4, 10, 4, 10));
        onTheme(() -> sessionBar.setBackground(UIUtils.surface(1)));

        JPanel sessionLeft = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        sessionLeft.setOpaque(false);
        JLabel sessLabel = new JLabel("Session:");
        sessLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        onTheme(() -> sessLabel.setForeground(UIUtils.mutedForeground()));
        sessionLeft.add(sessLabel);

        sessionSelectorCombo = new JComboBox<>();
        sessionSelectorCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
        sessionSelectorCombo.setPreferredSize(new Dimension(170, 24));
        sessionSelectorCombo.addActionListener(e -> onSessionSelected());
        sessionLeft.add(sessionSelectorCombo);

        newSessionBtn = new JButton("+ New");
        newSessionBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        newSessionBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        newSessionBtn.setToolTipText("Launch a new autonomous Agent Session");
        newSessionBtn.addActionListener(e -> promptNewSession());
        sessionLeft.add(newSessionBtn);

        renameSessionBtn = new JButton("Rename");
        renameSessionBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        renameSessionBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        renameSessionBtn.addActionListener(e -> promptRenameSession());
        sessionLeft.add(renameSessionBtn);

        closeSessionBtn = new JButton("✕");
        closeSessionBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        closeSessionBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        closeSessionBtn.setToolTipText("Close current session");
        closeSessionBtn.addActionListener(e -> closeActiveSession());
        sessionLeft.add(closeSessionBtn);

        loadSessionBtn = new JButton("Sessions ▾");
        loadSessionBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        loadSessionBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        loadSessionBtn.setToolTipText("Load, reload, or export/import project agent sessions");
        loadSessionBtn.addActionListener(e -> promptLoadOrImportSessions());
        sessionLeft.add(loadSessionBtn);

        sessionBar.add(sessionLeft, BorderLayout.CENTER);

        // 3. Provider, Model & Token Tracking Bar
        JPanel controlBar = new JPanel();
        controlBar.setLayout(new BoxLayout(controlBar, BoxLayout.Y_AXIS));
        controlBar.setOpaque(false);
        controlBar.setBorder(new EmptyBorder(2, 2, 2, 2));

        // Line A: Provider & Model Selector + Auto Route
        JPanel modelLine = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        modelLine.setOpaque(false);

        JLabel provLabel = new JLabel("Provider:");
        provLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        onTheme(() -> provLabel.setForeground(UIUtils.mutedForeground()));
        modelLine.add(provLabel);

        providerCombo = new JComboBox<>();
        providerCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
        providerCombo.setPreferredSize(new Dimension(130, 22));
        providerCombo.addActionListener(e -> onProviderChanged());
        modelLine.add(providerCombo);

        JLabel modLabel = new JLabel("Model:");
        modLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        onTheme(() -> modLabel.setForeground(UIUtils.mutedForeground()));
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
        onTheme(() -> tokenUsageLabel.setForeground(UIUtils.mutedForeground()));
        tokenLeft.add(tokenUsageLabel);

        tokenProgressBar = new JProgressBar(0, 100);
        tokenProgressBar.setPreferredSize(new Dimension(120, 14));
        tokenProgressBar.setStringPainted(true);
        tokenProgressBar.setFont(new Font("SansSerif", Font.BOLD, 9));
        tokenProgressBar.setForeground(new Color(60, 180, 75));
        tokenLeft.add(tokenProgressBar);

        compressBtn = new JButton("⚡ Compress");
        compressBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        compressBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        compressBtn.setToolTipText("Manually trigger 95% context compression utility to preserve context window");
        compressBtn.addActionListener(e -> triggerManualCompression());

        tokenLine.add(tokenLeft, BorderLayout.WEST);
        tokenLine.add(compressBtn, BorderLayout.EAST);

        controlBar.add(modelLine);
        controlBar.add(tokenLine);

        // 4. Quick Action Chips Panel
        quickActions.clear();
        quickActions.add(createChip("⚡ Explain", "Explain this code in detail and highlight key logic"));
        quickActions.add(createChip("🛠 Refactor", "Refactor and modernize this code for clarity and performance"));
        quickActions.add(createChip("🧪 Add Tests", "Generate comprehensive JUnit 5 test cases for this code"));
        quickActions.add(createChip("🐛 Find Bugs", "Diagnose potential bugs, security issues, and edge cases"));
        quickActions.add(createChip("🧠 View Memory", "Show all project rules and stored agent memories"));
        quickActions.add(createFeatureChip("📋 Plan Canvas", () -> new com.github.axiomate.agentic.ide.features.ui.LivingPlanDialog(null).setVisible(true)));
        quickActions.add(createFeatureChip("📊 Analytics", () -> new com.github.axiomate.agentic.ide.features.ui.AnalyticsDashboardDialog(null).setVisible(true)));
        quickActions.add(createFeatureChip("🎛 Autonomy", () -> new com.github.axiomate.agentic.ide.features.ui.ExecutionAndAutonomyDialog(null).setVisible(true)));

        // 5. Output Display Filtering & Visibility Bar
        JPanel outputDisplayBar = new JPanel(new BorderLayout(4, 0));
        onTheme(() -> {
            outputDisplayBar.setBorder(new CompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, UIUtils.borderColor()),
                    new EmptyBorder(2, 6, 2, 6)));
            outputDisplayBar.setBackground(UIUtils.surface(1));
        });

        JPanel filtersLeft = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 1));
        filtersLeft.setOpaque(false);

        JLabel filterLabel = new JLabel("Output:");
        filterLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
        onTheme(() -> filterLabel.setForeground(UIUtils.mutedForeground()));
        filtersLeft.add(filterLabel);

        showToolCallsCheck = new JCheckBox("🔧 Requests", true);
        showToolCallsCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        onTheme(() -> showToolCallsCheck.setForeground(UIUtils.accentText(UIUtils.ACCENT_COLOR)));
        showToolCallsCheck.setToolTipText("Show or hide tool calling requests and input arguments");
        showToolCallsCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showToolCallsCheck);

        showToolResultsCheck = new JCheckBox("📥 Responses", true);
        showToolResultsCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        onTheme(() -> showToolResultsCheck.setForeground(UIUtils.accentText(UIUtils.SUCCESS_COLOR)));
        showToolResultsCheck.setToolTipText("Show or hide tool execution results and stdout");
        showToolResultsCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showToolResultsCheck);

        showThinkingCheck = new JCheckBox("🧠 Reasoning", true);
        showThinkingCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        onTheme(() -> showThinkingCheck.setForeground(UIUtils.accentText(UIUtils.ACCENT_PURPLE)));
        showThinkingCheck.setToolTipText("Show or hide model thinking and reasoning blocks");
        showThinkingCheck.addActionListener(e -> applyDisplayFilters());
        filtersLeft.add(showThinkingCheck);

        showWalkthroughCheck = new JCheckBox("📝 Walkthrough", true);
        showWalkthroughCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        onTheme(() -> showWalkthroughCheck.setForeground(UIUtils.accentText(UIUtils.WARNING_COLOR)));
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
        collapseAllBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        collapseAllBtn.setToolTipText("Collapse all collapsible bubbles");
        collapseAllBtn.addActionListener(e -> collapseAllCards());
        actionsRight.add(collapseAllBtn);

        expandAllBtn = new JButton("▾ Expand All");
        expandAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        expandAllBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        expandAllBtn.setToolTipText("Expand all collapsible bubbles");
        expandAllBtn.addActionListener(e -> expandAllCards());
        actionsRight.add(expandAllBtn);

        toggleCategoriesBtn = new JButton("▾ Categories");
        toggleCategoriesBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        toggleCategoriesBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        toggleCategoriesBtn.setToolTipText("Expand or collapse specific output categories");
        toggleCategoriesBtn.addActionListener(e -> showCategoriesToggleMenu(toggleCategoriesBtn));
        actionsRight.add(toggleCategoriesBtn);

        outputDisplayBar.add(filtersLeft, BorderLayout.CENTER);
        outputDisplayBar.add(actionsRight, BorderLayout.EAST);

        // Session picker sits in the header, Claude-app style ("Session title ⌄"); everything else lives in the ⋯ menu
        sessionSelectorCombo.setPreferredSize(new Dimension(260, 26));
        sessionSelectorCombo.setFont(UIUtils.uiFont(Font.BOLD, 13f));
        sessionSelectorCombo.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0; arc: 8");
        titleSubPanel.remove(titleLabel);
        titleSubPanel.add(sessionSelectorCombo);
        titleSubPanel.add(statusBadge);
        onTheme(() -> headerPanel.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtils.borderColor()), new EmptyBorder(6, 12, 6, 12))));
        add(headerPanel, BorderLayout.NORTH);

        // 5. Chat Messages Container
        chatBox = new ScrollablePanel(null); // tracks the viewport width so bubbles wrap instead of overflowing
        chatBox.setLayout(new BoxLayout(chatBox, BoxLayout.Y_AXIS));
        chatBox.setBorder(new EmptyBorder(16, 16, 16, 16));

        chatScrollPane = new JScrollPane(chatBox);
        chatScrollPane.setBorder(BorderFactory.createEmptyBorder()); // null would be replaced by the theme border on theme switch
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(chatScrollPane, BorderLayout.CENTER);

        // 6. Input Area at Bottom
        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BoxLayout(inputPanel, BoxLayout.Y_AXIS));
        inputPanel.setBorder(new EmptyBorder(6, 16, 10, 16));
        onTheme(() -> inputPanel.setBackground(UIUtils.panelBackground()));

        includeContextCheck = new JCheckBox("Active File Context", true);
        includeContextCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        includeContextCheck.setOpaque(false);

        includeMemoryCheck = new JCheckBox("Agentic Memory", true);
        includeMemoryCheck.setFont(new Font("SansSerif", Font.PLAIN, 11));
        includeMemoryCheck.setOpaque(false);

        inputArea = new JTextArea(3, 20);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(transcriptFont(Font.PLAIN, 0f));
        inputArea.setBorder(new EmptyBorder(6, 2, 6, 6));
        inputArea.setOpaque(false);
        inputArea.setToolTipText("Type your prompt... Type '@' to mention files, '/' for slash commands");
        inputArea.putClientProperty("JTextField.placeholderText", "Ask Axiomate…  @ mention files · / commands · Ctrl+Enter to send");

        fileMentionController = new FileMentionController(inputArea);
        slashCompletion = new SlashCommandCompletion(inputArea);
        installImageTransfer();

        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && (e.isControlDown() || e.isMetaDown())) {
                    e.consume();
                    submitPrompt();
                }
                // Esc interrupts a running agent, like Claude Code (popups consume Esc first when open)
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !e.isConsumed() && thinkingIndicator.isRunning()
                        && !slashCompletion.isPopupVisible()) {
                    e.consume();
                    cancelAgent();
                }
            }
        });

        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(BorderFactory.createEmptyBorder());
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.setViewportBorder(null);
        onTheme(() -> {
            inputArea.setBackground(UIUtils.surface(3));
            inputScroll.getViewport().setBackground(UIUtils.surface(3));
        });

        // Claude Code style prompt box: rounded border with a "> " prompt, accent border while focused
        JPanel promptBox = new JPanel(new BorderLayout(6, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtils.surface(3));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(inputArea.isFocusOwner() ? UIUtils.ACCENT_COLOR : UIUtils.borderColor());
                g2.setStroke(new BasicStroke(inputArea.isFocusOwner() ? 1.6f : 1f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
            }
        };
        promptBox.setOpaque(false);
        promptBox.setBorder(new EmptyBorder(4, 10, 4, 6));
        JLabel promptGlyph = new JLabel(">");
        promptGlyph.setFont(transcriptFont(Font.BOLD, 1f));
        promptGlyph.setVerticalAlignment(SwingConstants.TOP);
        promptGlyph.setBorder(new EmptyBorder(6, 0, 0, 0));
        onTheme(() -> promptGlyph.setForeground(UIUtils.mutedForeground()));
        promptBox.add(promptGlyph, BorderLayout.WEST);
        promptBox.add(inputScroll, BorderLayout.CENTER);
        promptBox.setPreferredSize(new Dimension(0, 92));
        promptBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        inputArea.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                promptBox.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                promptBox.repaint();
            }
        });

        stopBtn = new JButton("Stop", UIUtils.createStopIcon(12, UIUtils.ERROR_COLOR));
        stopBtn.setEnabled(false);
        stopBtn.setToolTipText("Stop the agent (Esc)");
        stopBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        stopBtn.addActionListener(e -> cancelAgent());

        sendBtn = new JButton("↵");
        sendBtn.setFont(UIUtils.uiFont(Font.BOLD, 14f));
        sendBtn.setFocusable(false);
        sendBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendBtn.setToolTipText("Send (Ctrl+Enter)");
        sendBtn.putClientProperty("FlatLaf.style", "arc: 999; borderWidth: 0");
        sendBtn.setPreferredSize(new Dimension(34, 30));
        onTheme(() -> {
            sendBtn.setBackground(UIUtils.ACCENT_COLOR);
            sendBtn.setForeground(Color.WHITE);
        });
        sendBtn.addActionListener(e -> submitPrompt());
        JPanel sendHolder = new JPanel(new BorderLayout());
        sendHolder.setOpaque(false);
        sendHolder.add(sendBtn, BorderLayout.SOUTH);
        promptBox.add(sendHolder, BorderLayout.EAST);

        // Footer under the prompt: model picker on the left, context usage and Stop on the right
        providerCombo.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0");
        modelCombo.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0");
        providerCombo.setPreferredSize(new Dimension(120, 24));
        modelCombo.setPreferredSize(new Dimension(190, 24));
        JPanel footLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        footLeft.setOpaque(false);
        JButton attachBtn = UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.PLUS, 16, null),
                "Attach images for vision models (you can also paste or drop them)", e -> chooseImages());
        footLeft.add(attachBtn);
        footLeft.add(providerCombo);
        footLeft.add(modelCombo);
        JPanel footRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footRight.setOpaque(false);
        footRight.add(tokenUsageLabel);
        footRight.add(stopBtn);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(footLeft, BorderLayout.WEST);
        footer.add(footRight, BorderLayout.EAST);
        footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        thinkingIndicator.setAlignmentX(Component.LEFT_ALIGNMENT);
        promptBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Pending images sit just above the prompt, with a note on whether the chosen model can see them
        visionHint.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        visionHint.setBorder(new EmptyBorder(0, 4, 2, 0));
        visionHint.setVisible(false);
        attachmentStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        visionHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        inputPanel.add(thinkingIndicator);
        inputPanel.add(Box.createVerticalStrut(2));
        inputPanel.add(attachmentStrip);
        inputPanel.add(visionHint);
        inputPanel.add(promptBox);
        inputPanel.add(Box.createVerticalStrut(4));
        inputPanel.add(footer);

        // Keep the transcript and prompt in one readable centered column, like the Claude app
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                centerColumn(inputPanel);
            }
        });

        add(inputPanel, BorderLayout.SOUTH);

        // Register session and configuration change listeners
        SessionManager.getInstance().addSessionChangeListener(() -> {
            refreshSessionUi();
            AgentSession active = SessionManager.getInstance().getActiveSession();
            if (active != null && !active.getId().equals(displayedSessionId)) {
                reloadChatFromSession();
            }
        });
        ConfigManager.getInstance().addListener(updatedCfg -> refreshSessionUi());
        UIUtils.addThemeListener(() -> {
            themeAppliers.forEach(Runnable::run);
            reloadChatFromSession();
        });
        registerBuiltinCommands();

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
        updateVisionHint();
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
        currentAssistantMessagePanel = null;
        currentAssistantTextArea = null;
        AgentSession session = SessionManager.getInstance().getActiveSession();
        displayedSessionId = session != null ? session.getId() : null;
        if (session == null || session.getMessages().isEmpty()) {
            addWelcomeMessage();
        } else {
            for (AgentMessage msg : session.getMessages()) {
                if (msg.isUser()) {
                    appendUserBubble(msg.getContent(), attachmentsOf(msg));
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

        JMenuItem duplicateItem = new JMenuItem("⧉ Duplicate Active Session");
        duplicateItem.addActionListener(e -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) SessionManager.getInstance().duplicateSession(s.getId());
        });
        JMenuItem markdownItem = new JMenuItem("📝 Export Active Session as Markdown…");
        markdownItem.addActionListener(e -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) SessionsPanel.exportMarkdown(this, s);
        });
        JMenuItem externalItem = new JMenuItem("⤓ Import from Claude Code / Codex…");
        externalItem.addActionListener(e -> ideActions.openExternalSessionImport());
        JMenuItem managerItem = new JMenuItem("🗂 Session Manager (All Projects)…");
        managerItem.addActionListener(e -> ideActions.openSessionManager());

        menu.add(duplicateItem);
        menu.add(markdownItem);
        menu.addSeparator();
        menu.add(reloadItem);
        menu.add(managerItem);
        menu.addSeparator();
        menu.add(exportItem);
        menu.add(importItem);
        menu.add(externalItem);
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

    private JButton createFeatureChip(String text, Runnable action) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setFocusPainted(false);
        btn.addActionListener(e -> action.run());
        return btn;
    }

    /**
     * Claude Code style welcome box: accent-bordered card with the essentials and the working directory.
     */
    private void addWelcomeMessage() {
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        JPanel box = roundedPanel(UIUtils.panelBackground(), UIUtils.ACCENT_COLOR);
        box.setLayout(new BorderLayout(0, 6));
        box.setBorder(new EmptyBorder(10, 14, 10, 14));
        JLabel title = new JLabel("Welcome to Axiomate!", UIUtils.glyph(UIUtils.Glyph.SPARK, 14, UIUtils.ACCENT_COLOR), JLabel.LEFT);
        title.setIconTextGap(8);
        title.setFont(transcriptFont(Font.BOLD, 0f));
        title.setForeground(UIUtils.foreground());
        JTextArea body = createBubbleTextArea(
                "/help for commands · @ to mention files · Ctrl+Enter to send · Esc to interrupt\n\n"
                        + "cwd: " + (dir != null ? dir.getAbsolutePath() : "(no project)"));
        body.setForeground(UIUtils.mutedForeground());
        body.setFont(transcriptFont(Font.PLAIN, -1f));
        box.add(title, BorderLayout.NORTH);
        box.add(body, BorderLayout.CENTER);

        // A walkthrough card so the output filters treat it like any assistant reply
        MessageCard card = new MessageCard(MessageDisplayType.WALKTHROUGH, box, null);
        card.setBorder(new EmptyBorder(8, 4, 8, 4));
        card.add(box, BorderLayout.CENTER);
        card.setVisible(showWalkthroughCheck.isSelected());
        addCard(card, 6);
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

    /**
     * Puts text into the prompt box without sending it (e.g. "/review " from the command palette).
     */
    public void prefillPrompt(String text) {
        inputArea.setText(text);
        inputArea.setCaretPosition(inputArea.getDocument().getLength());
        inputArea.requestFocusInWindow();
    }

    public void sendPromptDirectly(String prompt) {
        inputArea.setText(prompt);
        submitPrompt();
    }

    private void submitPrompt() {
        String prompt = inputArea.getText().trim();
        if (prompt.isEmpty() && attachmentStrip.isEmpty()) return;
        if (prompt.isEmpty()) prompt = "Describe the attached image(s).";

        Dispatch dispatch = SlashCommandRegistry.getInstance().dispatch(prompt);
        switch (dispatch.outcome()) {
            case EXECUTED -> {
                inputArea.setText("");
                return;
            }
            case UNKNOWN -> {
                appendSystemBubble("Unknown command: " + prompt.split("\\s+")[0] + "\nType /help to list available slash commands.");
                return;
            }
            case EXPANDED -> {
                appendSystemBubble("↳ /" + dispatch.command().name() + " expanded (" + dispatch.command().source() + ")");
                prompt = dispatch.prompt();
            }
            default -> {
            }
        }

        AIAgentService agentService = AgentManager.getInstance().getActiveService();
        if (agentService.isBusy()) {
            JOptionPane.showMessageDialog(this, "Agent is currently processing a task. Please wait or click Stop.", "Busy", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<ImageAttachment> images = collectImages(prompt);
        inputArea.setText("");
        attachmentStrip.clear();
        appendUserBubble(prompt, images);

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

        // Services that save the conversation themselves must not get every step saved a second time
        boolean serviceRecords = agentService.recordsSessionMessages();
        agentService.sendMessage(prompt, contextCode, activeFilePath, images, new AgentListener() {
            @Override
            public void onToken(String token) {
                SwingUtilities.invokeLater(() -> {
                    boolean follow = isFollowingTranscript();
                    endLiveThinking();
                    ensureAssistantBubble();
                    currentStreamingBuffer.append(token);
                    currentAssistantTextArea.append(token);
                    setAgentState("Responding…", UIUtils.ACCENT_COLOR, true);
                    if (follow) scrollToBottom();
                });
            }

            @Override
            public void onReasoningToken(String token) {
                SwingUtilities.invokeLater(() -> {
                    boolean follow = isFollowingTranscript();
                    appendLiveThinking(token);
                    if (follow) scrollToBottom();
                });
            }

            @Override
            public void onThinking(String thought) {
                SwingUtilities.invokeLater(() -> {
                    endLiveThinking();
                    setAgentState("Thinking...", UIUtils.WARNING_COLOR, true);
                    if (!serviceRecords) recordSessionMessageIfNew(new AgentMessage(AgentRole.THINKING, thought, null));
                    appendThinkingBubble(thought);
                    terminalPanel.appendAgentLog("AGENT REASONING", thought);
                });
            }

            @Override
            public void onToolCall(String toolName, String input) {
                SwingUtilities.invokeLater(() -> {
                    // Text streamed before a tool call stays in its own bubble; the next reply starts a new one
                    endLiveThinking();
                    currentAssistantMessagePanel = null;
                    currentAssistantTextArea = null;
                    currentStreamingBuffer = new StringBuilder();
                    setAgentState("Running tool: " + toolName, UIUtils.ACCENT_COLOR, true);
                    if (!serviceRecords) recordSessionMessageIfNew(new AgentMessage(AgentRole.TOOL_CALL, input, toolName));
                    appendToolRequestBubble(toolName, input);
                    terminalPanel.appendAgentLog("TOOL INVOCATION: " + toolName, input);
                });
            }

            @Override
            public void onToolResult(String toolName, String output) {
                SwingUtilities.invokeLater(() -> {
                    if (!serviceRecords) recordSessionMessageIfNew(new AgentMessage(AgentRole.TOOL, output, toolName));
                    appendToolResultBubble(toolName, output);
                    terminalPanel.appendAgentLog("TOOL RESULT: " + toolName, output);
                });
            }

            @Override
            public void onComplete(String fullResponse) {
                SwingUtilities.invokeLater(() -> {
                    endLiveThinking();
                    // The final text is authoritative (e.g. a stream that differs from the assembled answer)
                    if (currentAssistantTextArea != null && fullResponse != null && !fullResponse.isBlank()
                            && !currentAssistantTextArea.getText().equals(fullResponse)) {
                        currentAssistantTextArea.setText(fullResponse);
                    }
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
                    endLiveThinking();
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
        endLiveThinking();
        setAgentState("Ready", UIUtils.SUCCESS_COLOR, false);
    }

    private void setAgentState(String status, Color color, boolean isBusy) {
        statusBadge.setText(status);
        statusBadge.setForeground(isBusy ? UIUtils.ACCENT_COLOR : color);
        sendBtn.setEnabled(!isBusy);
        stopBtn.setEnabled(isBusy);
        if (isBusy) {
            thinkingIndicator.start(status);
        } else {
            thinkingIndicator.stop();
        }
    }

    private JButton createCollapseToggleButton() {
        return createCollapseToggleButton(false);
    }

    private JButton createCollapseToggleButton(boolean startCollapsed) {
        JButton btn = new JButton(startCollapsed ? "expand" : "collapse");
        btn.setFont(transcriptFont(Font.PLAIN, -2f));
        btn.setForeground(UIUtils.mutedForeground());
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(0, 4, 0, 4));
        return btn;
    }

    // ------------------------------------------------------------------
    // Claude Code style transcript: "> prompt", "● reply", "● Tool(args)" + "⎿ result", "✻ Thinking"
    // ------------------------------------------------------------------

    /** Monospace transcript font, like the Claude Code terminal UI. */
    private static Font transcriptFont(int style, float delta) {
        Font f = UIUtils.getEditorFont(13);
        return f.deriveFont(style, f.getSize2D() + delta);
    }

    /** A transcript row: a fixed-width gutter glyph followed by the content (header + optional body). */
    private static JPanel transcriptRow(Icon gutterIcon, JComponent content) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel gutter = new JLabel(gutterIcon);
        gutter.setVerticalAlignment(SwingConstants.TOP);
        gutter.setBorder(new EmptyBorder(3, 0, 0, 0));
        gutter.setPreferredSize(new Dimension(16, 16));
        row.add(gutter, BorderLayout.WEST);
        row.add(content, BorderLayout.CENTER);
        return row;
    }

    /** Header line of a collapsible entry: title (truncates) + expand/collapse link, clickable as a whole. */
    private static JPanel entryHeader(JComponent title, JButton toggle) {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.add(title, BorderLayout.CENTER);
        if (toggle != null) header.add(toggle, BorderLayout.EAST);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return header;
    }

    private static void wireToggle(MessageCard card, JButton toggle, JComponent header) {
        toggle.addActionListener(e -> card.setCollapsed(!card.isCollapsed()));
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                card.setCollapsed(!card.isCollapsed());
            }
        });
    }

    private void addCard(MessageCard card, int gap) {
        chatBox.add(card);
        chatBox.add(Box.createVerticalStrut(gap));
        scrollToBottom();
    }

    // ------------------------------------------------------------------
    // Images for vision models
    // ------------------------------------------------------------------

    /** Attaches an image file to the next prompt (Explorer, image viewer, drag and drop). */
    public void attachImageFile(File file) {
        try {
            attachmentStrip.add(VisionSupport.fromFile(file));
            inputArea.requestFocusInWindow();
        } catch (Exception e) {
            Toast.warning(this, "Could not attach " + file.getName() + ": " + e.getMessage());
        }
    }

    /** Attaches an in-memory image (pasted screenshot). */
    public void attachImage(java.awt.Image image, String name) {
        try {
            attachmentStrip.add(VisionSupport.fromImage(image, name));
            inputArea.requestFocusInWindow();
        } catch (Exception e) {
            Toast.warning(this, "Could not attach the pasted image: " + e.getMessage());
        }
    }

    List<ImageAttachment> pendingImages() {
        return attachmentStrip.getImages();
    }

    private void chooseImages() {
        JFileChooser chooser = new JFileChooser(ProjectManager.getInstance().getCurrentProjectDirectory());
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Attach images");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Images (png, jpg, gif, webp, bmp)", VisionSupport.IMAGE_EXTENSIONS.toArray(String[]::new)));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) attachImageFile(f);
        }
    }

    /** Pending attachments plus '@'-mentioned image files; pasted images are saved into the project first. */
    private List<ImageAttachment> collectImages(String prompt) {
        List<ImageAttachment> out = new ArrayList<>();
        File projectDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        for (ImageAttachment img : attachmentStrip.getImages()) {
            try {
                out.add(VisionSupport.saveToProject(img, projectDir));
            } catch (Exception e) {
                log.warn("Could not save pasted image {}: {}", img.name(), e.getMessage());
                out.add(img);
            }
        }
        for (File f : FileMentionController.findMentionedImages(prompt, projectDir)) {
            boolean already = out.stream().anyMatch(i -> f.getAbsolutePath().equals(i.path()));
            if (already) continue;
            try {
                out.add(VisionSupport.fromFile(f));
            } catch (Exception e) {
                log.warn("Could not load mentioned image {}: {}", f, e.getMessage());
            }
        }
        return out;
    }

    private static List<ImageAttachment> attachmentsOf(AgentMessage msg) {
        List<ImageAttachment> out = new ArrayList<>();
        for (String path : msg.getAttachments()) {
            File f = new File(path);
            if (!f.isFile()) continue;
            String ext = VisionSupport.extension(f.getName());
            out.add(new ImageAttachment(f.getName(), VisionSupport.mimeFor(ext), null, f.getAbsolutePath(), 0, 0));
        }
        return out;
    }

    /** Shows whether the selected model will receive the pending images. */
    private void updateVisionHint() {
        if (attachmentStrip.isEmpty()) {
            visionHint.setVisible(false);
            return;
        }
        AgentSession session = SessionManager.getInstance().getActiveSession();
        IdeConfig cfg = ConfigManager.getInstance().getConfig();
        String model = session != null ? session.getModelId() : cfg.getActiveModelId();
        ProviderConfig prov = cfg.getProvider(session != null ? session.getProviderId() : cfg.getActiveProviderId());
        int n = attachmentStrip.getImages().size();
        if (VisionSupport.supportsVision(prov, model)) {
            visionHint.setText(n + " image" + (n == 1 ? "" : "s") + " will be sent to " + model);
            visionHint.setForeground(UIUtils.mutedForeground());
        } else {
            visionHint.setText("⚠ " + model + " can't view images. Pick a vision model, or enable Vision for it in Settings → Edit Model.");
            visionHint.setForeground(UIUtils.WARNING_COLOR);
        }
        visionHint.setVisible(true);
        revalidate();
    }

    /**
     * Paste (Ctrl+V) and drop images or image files into the prompt; everything else keeps the text
     * area's normal behaviour.
     */
    private void installImageTransfer() {
        TransferHandler text = inputArea.getTransferHandler();
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return hasImage(support.getDataFlavors()) || (text != null && text.canImport(support));
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (importImages(support.getTransferable())) return true;
                return text != null && text.importData(support);
            }

            @Override
            public int getSourceActions(JComponent c) {
                return text != null ? text.getSourceActions(c) : NONE;
            }

            @Override
            public void exportToClipboard(JComponent comp, java.awt.datatransfer.Clipboard clip, int action) {
                if (text != null) text.exportToClipboard(comp, clip, action);
            }

            @Override
            public void exportAsDrag(JComponent comp, java.awt.event.InputEvent e, int action) {
                if (text != null) text.exportAsDrag(comp, e, action);
            }
        };
        inputArea.setTransferHandler(handler);
        // Dropping onto the transcript attaches too
        chatBox.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return hasImage(support.getDataFlavors());
            }

            @Override
            public boolean importData(TransferSupport support) {
                return importImages(support.getTransferable());
            }
        });
    }

    private static boolean hasImage(java.awt.datatransfer.DataFlavor[] flavors) {
        for (java.awt.datatransfer.DataFlavor f : flavors) {
            if (f.equals(java.awt.datatransfer.DataFlavor.imageFlavor)
                    || f.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                return true;
            }
        }
        return false;
    }

    /** Attaches image files or pixel data from a clipboard/drop transfer. Returns false if it holds no images. */
    boolean importImages(java.awt.datatransfer.Transferable t) {
        try {
            if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) t.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                List<File> images = files.stream().filter(VisionSupport::isImageFile).toList();
                if (images.isEmpty()) return false;
                images.forEach(this::attachImageFile);
                return true;
            }
            if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                java.awt.Image img = (java.awt.Image) t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
                String name = "pasted-" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss")) + ".png";
                attachImage(img, name);
                return true;
            }
        } catch (Exception e) {
            log.warn("Could not import image: {}", e.getMessage());
        }
        return false;
    }

    private void appendUserBubble(String text) {
        appendUserBubble(text, List.of());
    }

    private void appendUserBubble(String text, List<ImageAttachment> images) {
        // Claude Code shows the prompt as "> text" on a subtle highlighted block
        JPanel inner = roundedPanel(UIUtils.surface(3), null);
        inner.setBorder(new EmptyBorder(6, 10, 6, 10));
        JLabel prompt = new JLabel(">");
        prompt.setFont(transcriptFont(Font.BOLD, 0f));
        prompt.setForeground(UIUtils.mutedForeground());
        prompt.setVerticalAlignment(SwingConstants.TOP);
        prompt.setBorder(new EmptyBorder(0, 0, 0, 8));
        JTextArea area = createBubbleTextArea(text);
        inner.add(prompt, BorderLayout.WEST);
        inner.add(area, BorderLayout.CENTER);
        if (!images.isEmpty()) {
            ImageAttachmentStrip strip = new ImageAttachmentStrip(false, null);
            images.forEach(strip::add);
            inner.add(strip, BorderLayout.SOUTH);
        }

        MessageCard card = new MessageCard(MessageDisplayType.USER, inner, null);
        card.setBorder(new EmptyBorder(8, 4, 4, 4));
        card.add(inner, BorderLayout.CENTER);
        addCard(card, 4);
    }

    private void ensureAssistantBubble() {
        if (currentAssistantMessagePanel == null) {
            JButton toggle = createCollapseToggleButton(false);
            currentAssistantTextArea = createBubbleTextArea("");

            JPanel contentPanel = new JPanel(new BorderLayout());
            contentPanel.setOpaque(false);
            contentPanel.add(currentAssistantTextArea, BorderLayout.CENTER);

            // The reply starts on the ● line; the collapse link sits at the right of the first line
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.add(toggle, BorderLayout.NORTH);
            JPanel body = new JPanel(new BorderLayout(6, 0));
            body.setOpaque(false);
            body.add(contentPanel, BorderLayout.CENTER);
            body.add(header, BorderLayout.EAST);

            MessageCard card = new MessageCard(MessageDisplayType.WALKTHROUGH, contentPanel, toggle, false);
            wireToggle(card, toggle, header);
            card.setBorder(new EmptyBorder(4, 4, 4, 4));
            card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.DOT, 14, UIUtils.foreground()), body), BorderLayout.CENTER);
            card.setVisible(showWalkthroughCheck.isSelected());
            addCard(card, 4);
            currentAssistantMessagePanel = card;
        }
    }

    /**
     * Renders model reasoning as Claude Code does: a muted "✻ Thinking" line, expandable to the full text.
     */
    /** A reasoning bubble's title line and text, so a streamed bubble can be filled in place. */
    private record ThinkingView(JLabel title, JTextArea area) {
    }

    static final int MAX_THINKING_DISPLAY = 4000;

    private static String thinkingTitle(String thought) {
        String firstLine = thought.strip().split("\\R", 2)[0];
        return "Thinking… " + (firstLine.length() > 90 ? firstLine.substring(0, 89) + "…" : firstLine);
    }

    private void appendLiveThinking(String delta) {
        if (liveThinking == null) {
            liveThinking = appendThinkingBubble("");
            liveThinkingBuffer = new StringBuilder();
            setAgentState("Thinking…", UIUtils.ACCENT_COLOR, true);
        }
        int before = liveThinkingBuffer.length();
        liveThinkingBuffer.append(delta);
        if (before < MAX_THINKING_DISPLAY) {
            String shown = liveThinkingBuffer.length() > MAX_THINKING_DISPLAY
                    ? delta.substring(0, MAX_THINKING_DISPLAY - before) + "\n… (full reasoning in Agent Logs)" : delta;
            liveThinking.area().append(shown);
        }
        if (before < 200) liveThinking.title().setText(thinkingTitle(liveThinkingBuffer.toString()));
    }

    /** Finishes the streamed reasoning bubble and records the reasoning in the agent log. */
    private void endLiveThinking() {
        if (liveThinking == null) return;
        terminalPanel.appendAgentLog("AGENT REASONING", liveThinkingBuffer.toString());
        liveThinking = null;
        liveThinkingBuffer = null;
    }

    /** True when the transcript is scrolled to (near) the bottom, so new streamed text should keep it there. */
    private boolean isFollowingTranscript() {
        JScrollBar bar = chatScrollPane.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 48;
    }

    private ThinkingView appendThinkingBubble(String thought) {
        String display = thought.length() > MAX_THINKING_DISPLAY
                ? thought.substring(0, MAX_THINKING_DISPLAY) + "\n… (full reasoning in Agent Logs)" : thought;
        JLabel title = new JLabel(thinkingTitle(display));
        title.setFont(transcriptFont(Font.ITALIC, -1f));
        title.setForeground(UIUtils.mutedForeground());

        JButton toggle = createCollapseToggleButton(true);
        JTextArea area = createBubbleTextArea(display);
        area.setFont(transcriptFont(Font.ITALIC, -1f));
        area.setForeground(UIUtils.mutedForeground());
        area.setBorder(new EmptyBorder(2, 0, 2, 0));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(area, BorderLayout.CENTER);
        JPanel header = entryHeader(title, toggle);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(header, BorderLayout.NORTH);
        body.add(contentPanel, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.THINKING, contentPanel, toggle, true);
        wireToggle(card, toggle, header);
        card.setBorder(new EmptyBorder(2, 4, 2, 4));
        card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.SPARK, 14, UIUtils.mutedForeground()), body), BorderLayout.CENTER);
        card.setVisible(showThinkingCheck.isSelected());
        addCard(card, 2);
        return new ThinkingView(title, area);
    }

    private void appendAssistantBubble(String text) {
        ensureAssistantBubble();
        currentAssistantTextArea.setText(text);
        currentAssistantMessagePanel = null;
        currentAssistantTextArea = null;
        scrollToBottom();
    }

    private void appendSystemBubble(String text) {
        JTextArea area = createBubbleTextArea(text);
        area.setForeground(UIUtils.mutedForeground());
        area.setFont(transcriptFont(Font.PLAIN, -1f));
        JPanel inner = new JPanel(new BorderLayout());
        inner.setOpaque(false);
        inner.add(area, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.SYSTEM, inner, null);
        card.setBorder(new EmptyBorder(2, 4, 2, 4));
        card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.ELBOW, 14, UIUtils.mutedForeground()), inner), BorderLayout.CENTER);
        addCard(card, 2);
    }

    /** "Tool(first argument…)" summary used in tool-call headers. */
    static String toolCallSummary(String toolName, String input) {
        String args = input == null ? "" : input.replaceAll("\\s+", " ").strip();
        // Like Claude Code: show the argument values, e.g. Read(src/Main.java) rather than raw JSON
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args);
            if (node != null && node.isObject()) {
                java.util.List<String> values = new java.util.ArrayList<>();
                node.fields().forEachRemaining(e -> values.add(e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
                args = String.join(", ", values).replaceAll("\\s+", " ").strip();
            }
        } catch (Exception ignored) {
            if (args.startsWith("{") && args.endsWith("}")) args = args.substring(1, args.length() - 1).strip();
        }
        if (args.length() > 70) args = args.substring(0, 69) + "…";
        return toolName + "(" + args + ")";
    }

    private void appendToolRequestBubble(String toolName, String input) {
        JLabel title = new JLabel(toolCallSummary(toolName, input));
        title.setFont(transcriptFont(Font.BOLD, 0f));
        title.setForeground(UIUtils.foreground());

        JButton toggle = createCollapseToggleButton(true);
        JTextArea area = createBubbleTextArea(input != null ? input : "{}");
        area.setFont(transcriptFont(Font.PLAIN, -1f));
        area.setForeground(UIUtils.mutedForeground());
        area.setBorder(new EmptyBorder(2, 0, 2, 0));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(area, BorderLayout.CENTER);
        JPanel header = entryHeader(title, toggle);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(header, BorderLayout.NORTH);
        body.add(contentPanel, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.TOOL_REQUEST, contentPanel, toggle, true);
        wireToggle(card, toggle, header);
        card.setBorder(new EmptyBorder(4, 4, 0, 4));
        card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.DOT, 14, UIUtils.SUCCESS_COLOR), body), BorderLayout.CENTER);
        card.setVisible(showToolCallsCheck.isSelected());
        addCard(card, 0);
    }

    private void appendToolResultBubble(String toolName, String output) {
        String text = output != null ? output : "(empty)";
        String[] lines = text.split("\r\n|\r|\n");
        boolean failed = text.startsWith("ERROR") || text.startsWith("[error]");
        String first = lines.length > 0 ? lines[0].strip() : "";
        if (first.length() > 90) first = first.substring(0, 89) + "…";
        JLabel title = new JLabel(first.isEmpty() ? "(no output)" : first);
        title.setFont(transcriptFont(Font.PLAIN, -1f));
        title.setForeground(failed ? UIUtils.ERROR_COLOR : UIUtils.mutedForeground());
        JLabel more = new JLabel(lines.length > 1 ? "… +" + (lines.length - 1) + " lines" : "");
        more.setFont(transcriptFont(Font.PLAIN, -2f));
        more.setForeground(UIUtils.mutedForeground());
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(more, BorderLayout.EAST);

        JButton toggle = createCollapseToggleButton(true);
        JTextArea area = createBubbleTextArea(text);
        area.setFont(transcriptFont(Font.PLAIN, -1f));
        area.setForeground(UIUtils.mutedForeground());
        area.setBorder(new EmptyBorder(2, 0, 2, 0));

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.add(area, BorderLayout.CENTER);
        JPanel header = entryHeader(titleRow, toggle);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(header, BorderLayout.NORTH);
        body.add(contentPanel, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.TOOL_RESPONSE, contentPanel, toggle, true);
        wireToggle(card, toggle, header);
        // Indented under the tool call, like Claude Code's "  ⎿  result"
        card.setBorder(new EmptyBorder(0, 22, 4, 4));
        card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.ELBOW, 14, UIUtils.mutedForeground()), body), BorderLayout.CENTER);
        card.setVisible(showToolResultsCheck.isSelected());
        addCard(card, 2);
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
        area.setFont(transcriptFont(Font.PLAIN, 0f));
        area.setForeground(UIUtils.foreground());
        return area;
    }

    /**
     * Registers a color assignment that is applied now and re-applied whenever the theme changes.
     */
    private void onTheme(Runnable applier) {
        themeAppliers.add(applier);
        applier.run();
    }

    /** A panel painted with rounded corners, used for chat bubbles. */
    private static JPanel roundedPanel(Color background, Color border) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(background);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                if (border != null) {
                    g2.setColor(border);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        return p;
    }

    /**
     * Connects the panel to frame-level actions (session manager, agent import/export, plugins).
     */
    public void setIdeActions(IdeActions actions) {
        this.ideActions = actions != null ? actions : IdeActions.NONE;
    }

    /** Readable width of the centered transcript/prompt column. */
    static final int COLUMN_WIDTH = 820;

    private void centerColumn(JPanel inputPanel) {
        int side = Math.max(16, (getWidth() - COLUMN_WIDTH) / 2);
        chatBox.setBorder(new EmptyBorder(16, side, 16, side));
        inputPanel.setBorder(new EmptyBorder(6, side, 10, side));
        revalidate();
    }

    private JPopupMenu buildOverflowMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenu session = new JMenu("Session");
        session.add(menuItem("New session…", this::promptNewSession));
        session.add(menuItem("Rename session…", this::promptRenameSession));
        session.add(menuItem("Close session", this::closeActiveSession));
        session.add(menuItem("Load / import sessions…", this::promptLoadOrImportSessions));
        menu.add(session);

        JMenu quick = new JMenu("Quick actions");
        for (JButton b : quickActions) quick.add(menuItem(b.getText(), b::doClick));
        menu.add(quick);

        JMenu output = new JMenu("Output");
        for (JCheckBox c : List.of(showToolCallsCheck, showToolResultsCheck, showThinkingCheck, showWalkthroughCheck)) {
            output.add(linkedCheck(c, c.getText().replaceAll("^\\S+\\s", "Show ")));
        }
        output.addSeparator();
        output.add(menuItem("Collapse all", collapseAllBtn::doClick));
        output.add(menuItem("Expand all", expandAllBtn::doClick));
        output.add(menuItem("Categories…", () -> showCategoriesToggleMenu(this)));
        menu.add(output);

        JMenu context = new JMenu("Context & model");
        context.add(linkedCheck(includeContextCheck, "Include active file"));
        context.add(linkedCheck(includeMemoryCheck, "Include agentic memory"));
        context.add(linkedCheck(autoRouteCheck, "Auto-route provider & model"));
        context.addSeparator();
        context.add(menuItem("Compress context now (" + tokenProgressBar.getString() + ")", compressBtn::doClick));
        menu.add(context);
        menu.addSeparator();
        menu.add(menuItem("Import memory from coding agents…", () -> ideActions.openMemoryImport()));
        menu.add(menuItem("Export memory to coding agents…", () -> ideActions.openMemoryExport()));
        menu.add(menuItem("Import memory from file…", () -> {
            terminalPanel.selectMemoryTab();
            terminalPanel.getMemoryPanel().importMemories();
        }));
        menu.addSeparator();
        menu.add(menuItem("Import sessions from Claude Code / Codex…", () -> ideActions.openExternalSessionImport()));
        menu.add(menuItem("Export session as Markdown…", () -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) SessionsPanel.exportMarkdown(this, s);
        }));
        menu.add(menuItem("Session Manager (all projects)…", () -> ideActions.openSessionManager()));
        menu.addSeparator();
        menu.add(menuItem("Plugins…", () -> ideActions.openPluginManager(1)));
        menu.add(menuItem("Slash commands…", () -> ideActions.openPluginManager(3)));
        menu.addSeparator();
        menu.add(menuItem("Clear conversation", this::clearChat));
        return menu;
    }

    private static JCheckBoxMenuItem linkedCheck(JCheckBox source, String text) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, source.isSelected());
        item.addActionListener(e -> source.doClick());
        return item;
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Built-in chat commands. Plugins and other agents' command folders add prompt commands on top.
     */
    private void registerBuiltinCommands() {
        SlashCommandRegistry reg = SlashCommandRegistry.getInstance();
        reg.register(new SlashCommand("help", "List available slash commands", null, "builtin", args -> {
            StringBuilder sb = new StringBuilder("Available slash commands:\n");
            for (SlashCommand c : reg.all()) {
                sb.append("  /").append(c.name()).append(" — ").append(c.description())
                        .append("builtin".equals(c.source()) ? "" : "  [" + c.source() + "]").append('\n');
            }
            appendSystemBubble(sb.toString().stripTrailing());
        }));
        reg.register(new SlashCommand("clear", "Clear the current conversation", null, "builtin", args -> clearChat()));
        reg.register(new SlashCommand("compact", "Compress the conversation context now", null, "builtin",
                args -> triggerManualCompression()));
        reg.register(new SlashCommand("new", "Start a new agent session: /new [name]", null, "builtin", args -> {
            IdeConfig cfg = ConfigManager.getInstance().getConfig();
            String name = args.isBlank() ? "Agent Session " + (SessionManager.getInstance().getSessions().size() + 1) : args.trim();
            SessionManager.getInstance().createSession(name, cfg.getActiveProviderId(), cfg.getActiveModelId(), cfg.isAutoRoutingEnabled());
        }));
        reg.register(new SlashCommand("rename", "Rename the current session: /rename <name>", null, "builtin", args -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null && !args.isBlank()) SessionManager.getInstance().renameSession(s.getId(), args.trim());
        }));
        reg.register(new SlashCommand("fork", "Duplicate the current session", null, "builtin", args -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) SessionManager.getInstance().duplicateSession(s.getId());
        }));
        reg.register(new SlashCommand("export", "Export the current session as Markdown", null, "builtin", args -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) SessionsPanel.exportMarkdown(this, s);
        }));
        reg.register(new SlashCommand("memory", "Show agent memory relevant to a topic: /memory [topic]", null, "builtin", args -> {
            terminalPanel.selectMemoryTab();
            String ctx = MemoryManager.getInstance().getMemoryStore().getRelevantContext(args);
            appendSystemBubble(ctx.isBlank() ? "No memories found." : ctx.strip());
        }));
        reg.register(new SlashCommand("sessions", "Open the sessions sidebar", null, "builtin",
                args -> ideActions.showSidebarView(IdeActions.VIEW_SESSIONS)));
        reg.register(new SlashCommand("plugins", "Open the Plugin Manager", null, "builtin", args -> ideActions.openPluginManager(0)));
        reg.register(new SlashCommand("import-memory", "Import memory from Claude Code, Codex, Cursor, Antigravity…", null,
                "builtin", args -> ideActions.openMemoryImport()));
        reg.register(new SlashCommand("export-memory", "Export memory to other coding agents", null, "builtin",
                args -> ideActions.openMemoryExport()));
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
            this(displayType, contentComponent, toggleBtn, false);
        }

        public MessageCard(MessageDisplayType displayType, JComponent contentComponent, JButton toggleBtn, boolean startCollapsed) {
            super(new BorderLayout());
            this.displayType = displayType;
            this.contentComponent = contentComponent;
            this.toggleBtn = toggleBtn;
            setOpaque(false);
            if (startCollapsed) {
                setCollapsed(true);
            }
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



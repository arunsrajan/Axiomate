package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AIAgentService;
import com.github.axiomate.agentic.ide.agent.AgentListener;
import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.AgentRunTracker;
import com.github.axiomate.agentic.ide.agent.ExecutionMode;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.ContextCompressor;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.SessionTask;
import com.github.axiomate.agentic.ide.agent.session.TaskBoard;
import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import com.github.axiomate.agentic.ide.agent.vision.ImageAttachment;
import com.github.axiomate.agentic.ide.agent.vision.VisionSupport;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.mcp.McpManager;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
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
    private final PromptHistory promptHistory = new PromptHistory();
    /** Set while the history puts a prompt into the box, so that edit does not end browsing. */
    private boolean recallingPrompt;
    private final List<JButton> quickActions = new ArrayList<>();
    private final ImageAttachmentStrip attachmentStrip = new ImageAttachmentStrip(true, this::updateVisionHint);
    private final JLabel visionHint = new JLabel();
    /** Plan / Manual / Auto for the active session. */
    private final JComboBox<ExecutionMode> modeCombo = new JComboBox<>(ExecutionMode.values());
    /** Above the prompt in Plan and Manual mode, so the mode is never a surprise. */
    private final JLabel modeHint = new JLabel();
    /** The agent run in progress, for the Tasks panel's activity list. */
    private AgentRunTracker.Run currentRun;
    /** "payments-api  ⎇ main": the project the agent works in and its checked-out git branch. */
    private final JLabel projectLabel = new JLabel();
    private final JLabel branchLabel = new JLabel();
    private String shownBranchKey;
    private final List<Runnable> themeAppliers = new ArrayList<>();
    private IdeActions ideActions = IdeActions.NONE;
    private String displayedSessionId;

    private JPanel currentAssistantMessagePanel;
    private JPanel currentAssistantContentPanel;
    private JTextArea currentAssistantTextArea;
    private StringBuilder currentStreamingBuffer;
    /** Incremented per prompt and on Stop; events of an older task are ignored. Changed on the event thread only. */
    private int taskGeneration;
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
        headerButtons.add(statusBadge);
        headerButtons.add(Box.createHorizontalStrut(4));
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

        modeCombo.setFont(new Font("SansSerif", Font.PLAIN, 11));
        modeCombo.setToolTipText("<html><b>Plan</b>: read and search only, then propose a plan<br>"
                + "<b>Manual</b>: ask before each tool that changes files or runs commands<br>"
                + "<b>Auto</b>: run tools without asking<br>Shift+Tab in the prompt box switches mode</html>");
        modeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof ExecutionMode m) {
                    if (index < 0) setText("Mode: " + m.label()); // the closed selector says what it is
                    else setToolTipText(m.description());
                }
                return this;
            }
        });
        modeCombo.addActionListener(e -> {
            if (updatingSessionUi) return;
            if (modeCombo.getSelectedItem() instanceof ExecutionMode m) setExecutionMode(m);
        });

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
        sessionSelectorCombo.setPreferredSize(new Dimension(220, 26));
        sessionSelectorCombo.setFont(UIUtils.uiFont(Font.BOLD, 13f));
        sessionSelectorCombo.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0; arc: 8");
        titleSubPanel.remove(titleLabel);
        titleSubPanel.add(sessionSelectorCombo);
        projectLabel.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        branchLabel.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        branchLabel.setIconTextGap(4);
        onTheme(() -> {
            projectLabel.setForeground(UIUtils.mutedForeground());
            branchLabel.setForeground(UIUtils.mutedForeground());
            branchLabel.setIcon(UIUtils.glyph(UIUtils.Glyph.BRANCH, 13, UIUtils.mutedForeground()));
        });
        // Project and branch take the space between the session picker and the buttons; status sits by the buttons
        JPanel whereLine = new JPanel(new BorderLayout(8, 0));
        whereLine.setOpaque(false);
        whereLine.setBorder(new EmptyBorder(0, 6, 0, 8));
        whereLine.add(projectLabel, BorderLayout.WEST);
        whereLine.add(branchLabel, BorderLayout.CENTER);
        headerPanel.add(whereLine, BorderLayout.CENTER);
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
        inputArea.setToolTipText("Type your prompt... Type '@' to mention files, '/' for slash commands, Ctrl+Up/Down for earlier prompts");
        inputArea.putClientProperty("JTextField.placeholderText", "Ask Axiomate…  @ mention files · / commands · Ctrl+Enter to send");

        fileMentionController = new FileMentionController(inputArea);
        slashCompletion = new SlashCommandCompletion(inputArea);
        installImageTransfer();

        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == '\t' && e.isShiftDown()) e.consume(); // Shift+Tab switched the mode instead
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && (e.isControlDown() || e.isMetaDown())) {
                    e.consume();
                    submitPrompt();
                }
                // Shift+Tab switches Plan → Manual → Auto, like Claude Code
                if (e.getKeyCode() == KeyEvent.VK_TAB && e.isShiftDown() && !e.isControlDown() && !e.isAltDown()) {
                    e.consume();
                    AgentSession s = SessionManager.getInstance().getActiveSession();
                    if (s != null) setExecutionMode(s.getExecutionMode().next());
                }
                // Prompt history: Ctrl+Up/Down anywhere, plain Up/Down in an empty box or while browsing
                // (the @ and / popups consume the arrows first while they are open)
                if (!e.isConsumed() && (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN)) {
                    boolean up = e.getKeyCode() == KeyEvent.VK_UP;
                    boolean ctrl = e.isControlDown() || e.isMetaDown();
                    boolean plain = !ctrl && !e.isShiftDown() && !e.isAltDown()
                            && (up ? (inputArea.getText().isEmpty() || promptHistory.isBrowsing()) && caretOnFirstLine()
                                   : promptHistory.isBrowsing() && caretOnLastLine());
                    if (ctrl || plain) {
                        String text = up ? promptHistory.previous(inputArea.getText()) : promptHistory.next();
                        if (text != null) {
                            e.consume();
                            showRecalledPrompt(text);
                        } else if (ctrl) {
                            e.consume();
                        }
                    }
                }
                // Esc interrupts a running agent, like Claude Code (popups consume Esc first when open)
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !e.isConsumed() && thinkingIndicator.isRunning()
                        && !slashCompletion.isPopupVisible()) {
                    e.consume();
                    cancelAgent();
                }
            }
        });

        // Typing into a recalled prompt makes it a new draft: the next Up starts again from the newest prompt
        inputArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { if (!recallingPrompt) promptHistory.reset(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { if (!recallingPrompt) promptHistory.reset(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
        seedPromptHistory();

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
        modeCombo.putClientProperty("FlatLaf.style", "borderWidth: 0; focusWidth: 0");
        modeCombo.setPreferredSize(new Dimension(118, 24));
        footLeft.add(modeCombo);
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
        modeHint.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        modeHint.setBorder(new EmptyBorder(0, 4, 2, 0));
        modeHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        modeHint.setVisible(false);
        inputPanel.add(modeHint);
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
        // The agent notifies from its own thread; Swing may only be touched on the event thread
        SessionManager.getInstance().addSessionChangeListener(() -> UIUtils.onEdt(() -> {
            refreshSessionUi();
            AgentSession active = SessionManager.getInstance().getActiveSession();
            if (active != null && !active.getId().equals(displayedSessionId)) {
                reloadChatFromSession();
            }
        }));
        ConfigManager.getInstance().addListener(updatedCfg -> UIUtils.onEdt(this::refreshSessionUi));
        ProjectManager.getInstance().addProjectChangeListener(dir -> SwingUtilities.invokeLater(this::updateProjectBranch));
        SessionManager.getInstance().addSessionChangeListener(() -> SwingUtilities.invokeLater(this::updateProjectBranch));
        // Branch switches made outside the IDE (terminal, other tools) show up within a few seconds
        Timer branchPoll = new Timer(3000, e -> {
            if (isShowing()) updateProjectBranch();
        });
        branchPoll.start();
        updateProjectBranch();
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
                ModelDefinition md = prov.findExactModel(model);
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
        // Refilling the combos auto-selects their first item; those events must not rewrite the session's model
        boolean wasUpdating = updatingSessionUi;
        updatingSessionUi = true;
        try {
            syncControls(session);
        } finally {
            updatingSessionUi = wasUpdating;
        }
    }

    private void syncControls(AgentSession session) {
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
            if (prov.findExactModel(session.getModelId()) == null && !session.getModelId().isBlank()) {
                // e.g. an imported session's model that is not configured: show it rather than switch models
                modelCombo.addItem(session.getModelId());
            }
            modelCombo.setSelectedItem(session.getModelId());
        }
        autoRouteCheck.setSelected(session.isAutoRoutingEnabled());
        modeCombo.setSelectedItem(session.getExecutionMode());
        updateModeHint(session.getExecutionMode());

        AgentManager.getInstance().updateActiveService(config);
    }

    private void updateTokenDisplay() {
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session == null) return;

        TokenTracker tracker = session.getTokenTracker();
        double pct = tracker.getUsagePercentage();
        int roundedPct = (int) Math.round(pct);

        tokenUsageLabel.setText(String.format("Tokens: %,d / %,d (%.1f%%)",
                tracker.getContextTokens(), tracker.getMaxContextTokens(), pct));

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
        currentAssistantContentPanel = null;
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
        seedPromptHistory();
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
        JMenuItem externalItem = new JMenuItem("⤓ Import from Claude / Codex / Gemini…");
        externalItem.addActionListener(e -> ideActions.openExternalSessionImport());
        JMenuItem managerItem = new JMenuItem("🗂 Session Manager (All Projects)…");
        managerItem.addActionListener(e -> ideActions.openSessionManager());

        JMenuItem copyConversationItem = new JMenuItem("📋 Copy Conversation as Markdown");
        copyConversationItem.addActionListener(e -> copyWithToast(conversationMarkdown(), "Conversation copied"));
        menu.add(duplicateItem);
        menu.add(copyConversationItem);
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
                submitPrompt(false);
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

    /** Prompts recalled with Ctrl+Up / Ctrl+Down. */
    public PromptHistory getPromptHistory() {
        return promptHistory;
    }

    private void showRecalledPrompt(String text) {
        recallingPrompt = true;
        try {
            inputArea.setText(text);
            inputArea.setCaretPosition(text.length());
        } finally {
            recallingPrompt = false;
        }
    }

    private boolean caretOnFirstLine() {
        try {
            return inputArea.getLineOfOffset(inputArea.getCaretPosition()) == 0;
        } catch (javax.swing.text.BadLocationException e) {
            return true;
        }
    }

    private boolean caretOnLastLine() {
        try {
            return inputArea.getLineOfOffset(inputArea.getCaretPosition()) == inputArea.getLineCount() - 1;
        } catch (javax.swing.text.BadLocationException e) {
            return true;
        }
    }

    /** Earlier prompts of the open session, so the history survives a restart. */
    private void seedPromptHistory() {
        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session == null) return;
        List<String> prompts = new ArrayList<>();
        for (AgentMessage m : session.getMessages()) {
            if (m.isUser()) prompts.add(m.getContent());
        }
        promptHistory.seed(prompts);
    }

    public void sendPromptDirectly(String prompt) {
        inputArea.setText(prompt);
        submitPrompt(false);
    }

    private void submitPrompt() {
        submitPrompt(true);
    }

    /** @param typedByUser record the prompt in the history (menu and chip prompts are not) */
    private void submitPrompt(boolean typedByUser) {
        String prompt = inputArea.getText().trim();
        String typed = prompt;
        if (prompt.isEmpty() && attachmentStrip.isEmpty()) return;
        if (prompt.isEmpty()) prompt = "Describe the attached image(s).";

        Dispatch dispatch = SlashCommandRegistry.getInstance().dispatch(prompt);
        switch (dispatch.outcome()) {
            case EXECUTED -> {
                if (typedByUser) promptHistory.add(typed);
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
        if (typedByUser) promptHistory.add(typed);
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
        currentAssistantContentPanel = null;
        currentAssistantTextArea = null;

        // Services that save the conversation themselves must not get every step saved a second time
        boolean serviceRecords = agentService.recordsSessionMessages();
        final int generation = ++taskGeneration;
        AgentSession taskSession = SessionManager.getInstance().getActiveSession();
        final String taskSessionId = taskSession != null ? taskSession.getId() : null;
        final ExecutionMode taskMode = taskSession != null ? taskSession.getExecutionMode() : ExecutionMode.AUTO;
        final AgentRunTracker.Run run = AgentRunTracker.getInstance().start(taskSessionId,
                taskSession != null ? taskSession.getName() : "", prompt, taskMode);
        currentRun = run;
        agentService.sendMessage(prompt, contextCode, activeFilePath, images, new AgentListener() {
            /** Still the latest task (not stopped or superseded). */
            private boolean current() {
                return generation == taskGeneration;
            }

            /** Its session is the one on screen: after switching sessions its output must not land in another. */
            private boolean visible() {
                return current() && java.util.Objects.equals(taskSessionId, displayedSessionId);
            }

            @Override
            public void onToken(String token) {
                SwingUtilities.invokeLater(() -> {
                    if (!visible()) return;
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
                    if (!visible()) return;
                    boolean follow = isFollowingTranscript();
                    appendLiveThinking(token);
                    if (follow) scrollToBottom();
                });
            }

            @Override
            public void onThinking(String thought) {
                SwingUtilities.invokeLater(() -> {
                    if (!visible()) return;
                    endLiveThinking();
                    setAgentState("Thinking...", UIUtils.WARNING_COLOR, true);
                    if (!serviceRecords) recordSessionMessageIfNew(new AgentMessage(AgentRole.THINKING, thought, null));
                    appendThinkingBubble(thought);
                    terminalPanel.appendAgentLog("AGENT REASONING", thought);
                });
            }

            @Override
            public void onToolCall(String toolName, String input) {
                if (current()) AgentRunTracker.getInstance().toolCall(run, toolName);
                SwingUtilities.invokeLater(() -> {
                    if (!visible()) return;
                    // Text streamed before a tool call stays in its own bubble; the next reply starts a new one
                    endLiveThinking();
                    if (currentAssistantContentPanel != null && currentStreamingBuffer != null && !currentStreamingBuffer.isEmpty()) {
                        populateAssistantContent(currentAssistantContentPanel, currentStreamingBuffer.toString());
                    }
                    currentAssistantMessagePanel = null;
                    currentAssistantContentPanel = null;
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
                    if (!visible()) return;
                    if (!serviceRecords) recordSessionMessageIfNew(new AgentMessage(AgentRole.TOOL, output, toolName));
                    appendToolResultBubble(toolName, output);
                    terminalPanel.appendAgentLog("TOOL RESULT: " + toolName, output);
                });
            }

            @Override
            public void onComplete(String fullResponse) {
                SwingUtilities.invokeLater(() -> {
                    if (!current()) return;
                    endLiveThinking();
                    // The final text is authoritative (e.g. a stream that differs from the assembled answer)
                    if (visible()) {
                        String finalReply = (fullResponse != null && !fullResponse.isBlank())
                                ? fullResponse
                                : (currentStreamingBuffer != null ? currentStreamingBuffer.toString() : "");
                        if (currentAssistantContentPanel != null) {
                            populateAssistantContent(currentAssistantContentPanel, finalReply);
                        } else if (currentAssistantTextArea != null && !finalReply.isBlank()
                                && !currentAssistantTextArea.getText().equals(finalReply)) {
                            currentAssistantTextArea.setText(finalReply);
                        }
                    }
                    if (currentAssistantMessagePanel != null) {
                        currentAssistantMessagePanel = null;
                        currentAssistantContentPanel = null;
                        currentAssistantTextArea = null;
                    }
                    setAgentState("Ready", UIUtils.SUCCESS_COLOR, false);
                    AgentRunTracker.getInstance().finish(run, AgentRunTracker.Status.COMPLETED, null);
                    if (taskMode == ExecutionMode.PLAN) {
                        String plan = fullResponse != null && !fullResponse.isBlank() ? fullResponse
                                : visible() && currentStreamingBuffer != null ? currentStreamingBuffer.toString() : "";
                        onPlanReady(taskSessionId, plan, visible());
                    }
                    updateTokenDisplay();
                    SessionManager.getInstance().autoSaveCurrentProjectSessions();
                    scrollToBottom();
                });
            }

            @Override
            public void onError(Throwable throwable) {
                SwingUtilities.invokeLater(() -> {
                    if (!current()) return;
                    endLiveThinking();
                    setAgentState("Error", UIUtils.ERROR_COLOR, false);
                    AgentRunTracker.getInstance().finish(run, AgentRunTracker.Status.FAILED, throwable.getMessage());
                    releasePlanWindow(taskSessionId, "The revision failed: " + throwable.getMessage());
                    if (visible()) appendAssistantBubble("⚠️ **Error occurred:** " + throwable.getMessage());
                    terminalPanel.appendAgentLog("ERROR", throwable.toString());
                    updateTokenDisplay();
                });
            }
        });
    }

    /** Stops the running agent task (Tasks panel, menus); nothing happens when the agent is idle. */
    public void stopAgent() {
        if (AgentManager.getInstance().getActiveService().isBusy() || currentRun != null
                && currentRun.status() == AgentRunTracker.Status.RUNNING) {
            cancelAgent();
        }
    }

    private void cancelAgent() {
        AgentRunTracker.getInstance().finish(currentRun, AgentRunTracker.Status.STOPPED, "Stopped by the user");
        if (currentRun != null) releasePlanWindow(currentRun.sessionId(), "Stopped");
        taskGeneration++; // whatever the stopped task still sends is ignored
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
        installMessageMenu(card);
        chatBox.add(card);
        chatBox.add(Box.createVerticalStrut(gap));
        scrollToBottom();
    }

    /** Shows the open project and its git branch in the header; hides the branch outside a git repository. */
    void updateProjectBranch() {
        File dir = ProjectManager.getInstance().getCurrentProjectDirectory();
        String branch = dir != null ? com.github.axiomate.agentic.ide.util.GitBranch.of(dir) : null;
        String key = (dir != null ? dir.getAbsolutePath() : "") + "|" + branch;
        if (key.equals(shownBranchKey)) return;
        shownBranchKey = key;
        projectLabel.setText(dir != null ? dir.getName() : "");
        projectLabel.setToolTipText(dir != null ? dir.getAbsolutePath() : null);
        projectLabel.setVisible(dir != null);
        branchLabel.setText(branch != null ? branch : "");
        branchLabel.setVisible(branch != null);
        branchLabel.setToolTipText(branch == null ? null : com.github.axiomate.agentic.ide.util.GitBranch.isDetached(branch)
                ? "Detached HEAD at " + branch : "Git branch: " + branch);
        revalidate();
        repaint();
    }

    String shownProject() {
        return projectLabel.isVisible() ? projectLabel.getText() : null;
    }

    String shownBranch() {
        return branchLabel.isVisible() ? branchLabel.getText() : null;
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
        card.setRawText(text);
        card.setBorder(new EmptyBorder(8, 4, 4, 4));
        card.add(inner, BorderLayout.CENTER);
        addCard(card, 4);
    }

    private void ensureAssistantBubble() {
        if (currentAssistantMessagePanel == null) {
            JButton toggle = createCollapseToggleButton(false);
            currentAssistantTextArea = createBubbleTextArea("");

            currentAssistantContentPanel = new JPanel();
            currentAssistantContentPanel.setLayout(new BoxLayout(currentAssistantContentPanel, BoxLayout.Y_AXIS));
            currentAssistantContentPanel.setOpaque(false);
            currentAssistantTextArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            currentAssistantContentPanel.add(currentAssistantTextArea);

            // The reply starts on the ● line; the collapse link sits at the right of the first line
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.add(toggle, BorderLayout.NORTH);
            JPanel body = new JPanel(new BorderLayout(6, 0));
            body.setOpaque(false);
            body.add(currentAssistantContentPanel, BorderLayout.CENTER);
            body.add(header, BorderLayout.EAST);

            MessageCard card = new MessageCard(MessageDisplayType.WALKTHROUGH, currentAssistantContentPanel, toggle, false);
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
        card.setRawText(thought);
        wireToggle(card, toggle, header);
        card.setBorder(new EmptyBorder(2, 4, 2, 4));
        card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.SPARK, 14, UIUtils.mutedForeground()), body), BorderLayout.CENTER);
        card.setVisible(showThinkingCheck.isSelected());
        addCard(card, 2);
        return new ThinkingView(title, area);
    }

    private void appendAssistantBubble(String text) {
        ensureAssistantBubble();
        populateAssistantContent(currentAssistantContentPanel, text);
        currentAssistantMessagePanel = null;
        currentAssistantContentPanel = null;
        currentAssistantTextArea = null;
        scrollToBottom();
    }

    private void populateAssistantContent(JPanel container, String text) {
        if (container == null) return;
        String content = text != null ? text : "";
        if (SwingUtilities.getAncestorOfClass(MessageCard.class, container) instanceof MessageCard owner) {
            owner.setRawText(content);
            SwingUtilities.invokeLater(() -> inheritMenu(owner)); // the rendered Markdown panels are new children
        }
        if (!MarkdownTableParser.containsMarkdown(content)) {
            if (container.getComponentCount() == 1 && container.getComponent(0) instanceof JTextArea ta) {
                if (!ta.getText().equals(content)) {
                    ta.setText(content);
                }
            } else {
                container.removeAll();
                JTextArea ta = createBubbleTextArea(content);
                ta.setAlignmentX(Component.LEFT_ALIGNMENT);
                container.add(ta);
            }
            container.revalidate();
            container.repaint();
            return;
        }

        List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parseSegments(content);
        boolean hasRich = segments.stream().anyMatch(s ->
                s instanceof MarkdownTableParser.TableSegment
                        || s instanceof MarkdownTableParser.CodeBlockSegment
                        || (s instanceof MarkdownTableParser.TextSegment ts && MarkdownTextPanel.hasMarkdownFormatting(ts.text()))
        );

        if (!hasRich) {
            if (container.getComponentCount() == 1 && container.getComponent(0) instanceof JTextArea ta) {
                if (!ta.getText().equals(content)) {
                    ta.setText(content);
                }
            } else {
                container.removeAll();
                JTextArea ta = createBubbleTextArea(content);
                ta.setAlignmentX(Component.LEFT_ALIGNMENT);
                container.add(ta);
            }
            container.revalidate();
            container.repaint();
            return;
        }

        container.removeAll();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));

        for (int i = 0; i < segments.size(); i++) {
            MarkdownTableParser.Segment seg = segments.get(i);
            if (seg instanceof MarkdownTableParser.TextSegment ts) {
                String s = ts.text();
                if (i > 0 && s.startsWith("\n")) s = s.substring(1);
                if (i < segments.size() - 1 && s.endsWith("\n")) s = s.substring(0, s.length() - 1);
                if (!s.isBlank()) {
                    if (MarkdownTextPanel.hasMarkdownFormatting(s)) {
                        MarkdownTextPanel textPanel = new MarkdownTextPanel(s);
                        textPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                        container.add(textPanel);
                    } else {
                        JTextArea area = createBubbleTextArea(s);
                        area.setAlignmentX(Component.LEFT_ALIGNMENT);
                        container.add(area);
                    }
                    container.add(Box.createVerticalStrut(6));
                }
            } else if (seg instanceof MarkdownTableParser.CodeBlockSegment cb) {
                MarkdownCodePanel codePanel = new MarkdownCodePanel(cb);
                codePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                container.add(codePanel);
                container.add(Box.createVerticalStrut(8));
            } else if (seg instanceof MarkdownTableParser.TableSegment tab) {
                MarkdownTablePanel tablePanel = new MarkdownTablePanel(tab);
                tablePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                container.add(tablePanel);
                container.add(Box.createVerticalStrut(8));
            }
        }
        container.revalidate();
        container.repaint();
    }

    private void appendSystemBubble(String text) {
        JTextArea area = createBubbleTextArea(text);
        area.setForeground(UIUtils.mutedForeground());
        area.setFont(transcriptFont(Font.PLAIN, -1f));
        JPanel inner = new JPanel(new BorderLayout());
        inner.setOpaque(false);
        inner.add(area, BorderLayout.CENTER);

        MessageCard card = new MessageCard(MessageDisplayType.SYSTEM, inner, null);
        card.setRawText(text);
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
        card.setRawText(toolName + " " + (input != null ? input : ""));
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
        card.setRawText(text);
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

    // ------------------------------------------------------------------ execution mode

    /** Switches the active session to {@code mode}, saves it, and updates the selector, the hint and the Tasks panel. */
    public void setExecutionMode(ExecutionMode mode) {
        AgentSession s = SessionManager.getInstance().getActiveSession();
        if (s == null || mode == null) return;
        boolean changed = s.getExecutionMode() != mode;
        s.setExecutionMode(mode);
        boolean wasUpdating = updatingSessionUi;
        updatingSessionUi = true;
        try {
            modeCombo.setSelectedItem(mode);
        } finally {
            updatingSessionUi = wasUpdating;
        }
        updateModeHint(mode);
        if (changed) {
            SessionManager.getInstance().autoSaveCurrentProjectSessions();
            SessionManager.getInstance().notifyListeners();
        }
    }

    private void updateModeHint(ExecutionMode mode) {
        switch (mode) {
            case PLAN -> {
                modeHint.setText("📝 Plan mode — the agent reads and plans; edits and commands are blocked (Shift+Tab to switch)");
                modeHint.setForeground(UIUtils.ACCENT_COLOR);
                modeHint.setVisible(true);
            }
            case MANUAL -> {
                modeHint.setText("✋ Manual mode — you approve each tool call that changes files or runs commands");
                modeHint.setForeground(UIUtils.WARNING_COLOR);
                modeHint.setVisible(true);
            }
            default -> modeHint.setVisible(false);
        }
    }

    /** /plan, /manual and /auto: switch, and send the rest of the line as the prompt when there is one. */
    private void switchModeAndSend(ExecutionMode mode, String request) {
        setExecutionMode(mode);
        if (request != null && !request.isBlank()) {
            SwingUtilities.invokeLater(() -> sendPromptDirectly(request.strip()));
        } else {
            Toast.info(this, mode.label() + " mode — " + mode.description());
        }
    }

    /** Opens the plan window of a session; tests replace it, since a window needs a screen. */
    private java.util.function.BiConsumer<AgentSession, ImplementationPlanPanel.Actions> planWindowOpener =
            (session, actions) -> com.github.axiomate.agentic.ide.ui.dialogs.ImplementationPlanDialog.open(
                    SwingUtilities.getWindowAncestor(this), session, actions);

    void setPlanWindowOpener(java.util.function.BiConsumer<AgentSession, ImplementationPlanPanel.Actions> opener) {
        if (opener != null) this.planWindowOpener = opener;
    }

    /** Sends the plan window's implement and revise prompts; tests replace it so no model is called. */
    private java.util.function.Consumer<String> planPromptSender = this::sendPromptDirectly;

    void setPlanPromptSender(java.util.function.Consumer<String> sender) {
        if (sender != null) this.planPromptSender = sender;
    }

    /**
     * A Plan-mode answer arrived: it is kept as the session's plan, its numbered steps go to the Tasks panel (unless
     * the agent already keeps an open task list), and it opens in the plan window.
     */
    void onPlanReady(String sessionId, String plan, boolean showInChat) {
        AgentSession s = SessionManager.getInstance().findSession(sessionId);
        if (s == null || plan == null || plan.isBlank()) return;
        s.setImplementationPlan(plan.strip());
        SessionManager.getInstance().autoSaveCurrentProjectSessions();
        if (showInChat) appendPlanActions(sessionId, plan);
        if (ConfigManager.getInstance().getConfig().isOpenPlanWindowAutomatically()) showPlanWindow(sessionId);
    }

    /** The chat's note under a plan: its steps went to the Tasks panel, and the plan window can be reopened. */
    void appendPlanActions(String sessionId, String plan) {
        AgentSession s = SessionManager.getInstance().findSession(sessionId);
        if (s == null || plan == null || plan.isBlank()) return;
        List<String> steps = TaskBoard.stepsFromPlan(plan);
        boolean added = false;
        if (!steps.isEmpty() && !TaskBoard.hasOpenTasks(s)) {
            List<SessionTask> tasks = new ArrayList<>();
            for (String step : steps) tasks.add(new SessionTask(step, SessionTask.Status.PENDING, "agent"));
            TaskBoard.getInstance().replace(s, tasks);
            added = true;
        }
        int n = s.getTasks().size();
        JLabel msg = new JLabel("<html><b>Plan ready.</b> " + (added ? steps.size() + " steps were added to the Tasks panel. "
                : n > 0 ? "The task list has " + n + " steps. " : "")
                + "Review it in the plan window, then implement it from there.</html>");
        msg.setFont(transcriptFont(Font.PLAIN, -1f));
        JPanel buttons = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        buttons.setOpaque(false);
        JButton open = new JButton("Open plan window");
        open.setToolTipText("The plan with its steps, and the buttons to implement or revise it");
        open.addActionListener(e -> showPlanWindow(sessionId));
        JButton showTasks = new JButton("Show tasks");
        showTasks.addActionListener(e -> ideActions.showSidebarView(com.github.axiomate.agentic.ide.ui.IdeActions.VIEW_TASKS));
        buttons.add(open);
        buttons.add(showTasks);
        JPanel inner = new JPanel(new BorderLayout(0, 4));
        inner.setOpaque(false);
        inner.add(msg, BorderLayout.NORTH);
        inner.add(buttons, BorderLayout.CENTER);
        MessageCard card = new MessageCard(MessageDisplayType.SYSTEM, inner, null);
        card.setRawText("Plan ready");
        card.setBorder(new EmptyBorder(4, 4, 4, 4));
        card.add(transcriptRow(UIUtils.glyph(UIUtils.Glyph.SPARK, 14, UIUtils.ACCENT_COLOR), inner), BorderLayout.CENTER);
        addCard(card, 6);
    }

    /** Shows the session's latest plan in its window (⋯ menu, /show-plan, "Open plan window"). */
    void showPlanWindow(String sessionId) {
        AgentSession s = SessionManager.getInstance().findSession(sessionId);
        if (s == null || s.getImplementationPlan().isBlank()) {
            Toast.info(this, "No implementation plan yet. Switch to Plan mode (/plan) and describe the change.");
            return;
        }
        planWindowOpener.accept(s, planWindowActions(sessionId));
    }

    private ImplementationPlanPanel.Actions planWindowActions(String sessionId) {
        return new ImplementationPlanPanel.Actions() {
            @Override
            public void implement(ExecutionMode mode, String prompt, List<String> steps) {
                implementPlan(sessionId, mode, prompt, steps);
            }

            @Override
            public void revise(String prompt) {
                if (!openSession(sessionId)) return;
                setExecutionMode(ExecutionMode.PLAN);
                planPromptSender.accept(prompt);
            }

            @Override
            public void showTasks() {
                ideActions.showSidebarView(com.github.axiomate.agentic.ide.ui.IdeActions.VIEW_TASKS);
            }
        };
    }

    /** Carries the plan out: the chosen steps become the task list, the mode switches and the prompt is sent. */
    void implementPlan(String sessionId, ExecutionMode mode, String prompt, List<String> steps) {
        if (!openSession(sessionId)) return;
        AgentSession s = SessionManager.getInstance().getActiveSession();
        if (!steps.isEmpty()) {
            List<SessionTask> tasks = new ArrayList<>();
            for (String step : steps) tasks.add(new SessionTask(step, SessionTask.Status.PENDING, "agent"));
            TaskBoard.getInstance().replace(s, tasks);
        }
        setExecutionMode(mode);
        planPromptSender.accept(prompt);
    }

    /** Makes {@code sessionId} the active session; false when it no longer exists. */
    private boolean openSession(String sessionId) {
        AgentSession active = SessionManager.getInstance().getActiveSession();
        if (active != null && active.getId().equals(sessionId)) return true;
        if (SessionManager.getInstance().findSession(sessionId) == null) return false;
        SessionManager.getInstance().switchSession(sessionId);
        return true;
    }

    /** A plan window waiting for a revision gets its buttons back when the run fails or is stopped. */
    private static void releasePlanWindow(String sessionId, String message) {
        ImplementationPlanPanel window = com.github.axiomate.agentic.ide.ui.dialogs.ImplementationPlanDialog.find(sessionId);
        if (window != null && window.isBusy()) window.setBusy(false, message);
    }

    // ------------------------------------------------------------------ copy and fork

    /** Right-click menu of a transcript entry: copy it, copy the conversation, fork from this point. */
    private void installMessageMenu(MessageCard card) {
        JPopupMenu menu = new JPopupMenu();
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                menu.removeAll();
                fillMessageMenu(menu, card); // built on opening: whether the agent is busy changes
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        card.setComponentPopupMenu(menu);
        inheritMenu(card);
    }

    /** Children without a menu of their own show the card's menu (text areas, rendered Markdown). */
    private static void inheritMenu(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JComponent j && j.getComponentPopupMenu() == null) j.setInheritsPopupMenu(true);
            if (k instanceof Container cc) inheritMenu(cc);
        }
    }

    void fillMessageMenu(JPopupMenu menu, MessageCard card) {
        String text = cardText(card);
        JMenuItem copy = menuItem("Copy message", () -> copyWithToast(text, "Message copied"));
        copy.setEnabled(!text.isBlank());
        menu.add(copy);
        menu.add(menuItem("Copy conversation as Markdown", () -> copyWithToast(conversationMarkdown(), "Conversation copied")));
        menu.add(menuItem("Copy last answer", () -> copyWithToast(lastAnswer(), "Last answer copied")));
        menu.addSeparator();

        int turn = turnOf(card);
        boolean busy = thinkingIndicator.isRunning();
        String busyTip = "Wait for the agent to finish, or stop it, first";
        if (card.getDisplayType() == MessageDisplayType.USER) {
            JMenuItem edit = menuItem("Edit & resend in a fork", () -> editAndResendInFork(turn));
            edit.setToolTipText("A new session with the conversation before this prompt, and the prompt in the box to change and send");
            edit.setEnabled(!busy);
            if (busy) edit.setToolTipText(busyTip);
            menu.add(edit);
        }
        JMenuItem fork = menuItem("Fork from here", () -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) forkActiveSession(AgentSession.endOfTurn(s.getMessages(), turn));
        });
        fork.setToolTipText(busy ? busyTip : "A new session with the conversation up to the end of this turn");
        fork.setEnabled(!busy);
        JMenuItem forkAll = menuItem("Fork whole session", () -> forkActiveSession(-1));
        forkAll.setEnabled(!busy);
        menu.add(fork);
        menu.add(forkAll);
    }

    /**
     * Which prompt's turn a card belongs to: 0 for the first prompt and the answers after it. Each prompt shows as
     * exactly one user card and is saved as exactly one user message, so the count matches the session.
     */
    int turnOf(MessageCard card) {
        int users = 0;
        for (Component c : chatBox.getComponents()) {
            if (c == card) return card.getDisplayType() == MessageDisplayType.USER ? users : users - 1;
            if (c instanceof MessageCard m && m.getDisplayType() == MessageDisplayType.USER) users++;
        }
        return users - 1;
    }

    /** Forks the active session keeping {@code endExclusive} messages (-1 for all) and opens the fork. */
    AgentSession forkActiveSession(int endExclusive) {
        AgentSession s = SessionManager.getInstance().getActiveSession();
        if (s == null) return null;
        int end = endExclusive < 0 ? s.getMessages().size() : endExclusive;
        AgentSession fork = SessionManager.getInstance().forkSession(s.getId(), end, null);
        if (fork != null) Toast.success(this, "Forked into '" + fork.getName() + "'");
        return fork;
    }

    /** A fork ending just before the {@code turn}-th prompt, with that prompt in the box to change and send. */
    AgentSession editAndResendInFork(int turn) {
        AgentSession s = SessionManager.getInstance().getActiveSession();
        if (s == null) return null;
        int index = AgentSession.indexOfUserMessage(s.getMessages(), turn);
        if (index < 0) return null;
        String original = s.getMessages().get(index).getContent();
        AgentSession fork = SessionManager.getInstance().forkSession(s.getId(), index, s.getName() + " (edit)");
        if (fork != null) {
            prefillPrompt(original);
            inputArea.selectAll();
            Toast.info(this, "Edit the prompt and send it — the original session is unchanged");
        }
        return fork;
    }

    private static String cardText(MessageCard card) {
        if (card.getRawText() != null) return card.getRawText();
        StringBuilder sb = new StringBuilder();
        collectText(card, sb);
        return sb.toString().strip();
    }

    private static void collectText(Container c, StringBuilder sb) {
        for (Component k : c.getComponents()) {
            if (k instanceof javax.swing.text.JTextComponent t) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t.getText());
            } else if (k instanceof Container cc) {
                collectText(cc, sb);
            }
        }
    }

    /** The active session as Markdown (prompts, answers and tool calls, without tool output). */
    String conversationMarkdown() {
        AgentSession s = SessionManager.getInstance().getActiveSession();
        return s == null || s.getMessages().isEmpty() ? ""
                : com.github.axiomate.agentic.ide.interop.SessionTranscriptExporter.toMarkdown(s, false);
    }

    /** The newest final answer of the active session. */
    String lastAnswer() {
        AgentSession s = SessionManager.getInstance().getActiveSession();
        if (s == null) return "";
        List<AgentMessage> messages = s.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage m = messages.get(i);
            if (m.isAssistant() && !m.isInterim() && !m.getContent().isBlank()) return m.getContent();
        }
        return "";
    }

    private void copyWithToast(String text, String done) {
        if (text == null || text.isBlank()) {
            Toast.info(this, "Nothing to copy yet");
        } else if (copyToClipboard(text)) {
            Toast.success(this, done);
        }
    }

    static boolean copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
            return true;
        } catch (Exception e) {
            return false; // headless, or the clipboard is held by another application
        }
    }

    private JPopupMenu buildOverflowMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenu session = new JMenu("Session");
        session.add(menuItem("New session…", this::promptNewSession));
        session.add(menuItem("Rename session…", this::promptRenameSession));
        session.add(menuItem("Close session", this::closeActiveSession));
        session.add(menuItem("Load / import sessions…", this::promptLoadOrImportSessions));
        session.addSeparator();
        session.add(menuItem("Fork session", () -> forkActiveSession(-1)));
        session.add(menuItem("Copy conversation as Markdown", () -> copyWithToast(conversationMarkdown(), "Conversation copied")));
        session.add(menuItem("Copy last answer", () -> copyWithToast(lastAnswer(), "Last answer copied")));
        menu.add(session);

        JMenu modes = new JMenu("Execution mode");
        AgentSession active = SessionManager.getInstance().getActiveSession();
        ButtonGroup group = new ButtonGroup();
        for (ExecutionMode m : ExecutionMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(m.label() + " — " + m.description(),
                    active != null && active.getExecutionMode() == m);
            item.addActionListener(e -> setExecutionMode(m));
            group.add(item);
            modes.add(item);
        }
        modes.addSeparator();
        JCheckBoxMenuItem askReads = new JCheckBoxMenuItem("Manual mode: also ask before read-only tools",
                ConfigManager.getInstance().getConfig().isManualModeAsksForReadOnlyTools());
        askReads.addActionListener(e -> {
            IdeConfig cfg = ConfigManager.getInstance().getConfig();
            cfg.setManualModeAsksForReadOnlyTools(askReads.isSelected());
            ConfigManager.getInstance().saveConfig(cfg);
        });
        modes.add(askReads);
        JCheckBoxMenuItem autoOpenPlan = new JCheckBoxMenuItem("Open the plan window when a plan is ready",
                ConfigManager.getInstance().getConfig().isOpenPlanWindowAutomatically());
        autoOpenPlan.addActionListener(e -> {
            IdeConfig cfg = ConfigManager.getInstance().getConfig();
            cfg.setOpenPlanWindowAutomatically(autoOpenPlan.isSelected());
            ConfigManager.getInstance().saveConfig(cfg);
        });
        modes.add(autoOpenPlan);
        JMenuItem showPlan = menuItem("Show implementation plan…", () -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) showPlanWindow(s.getId());
        });
        showPlan.setEnabled(active != null && !active.getImplementationPlan().isBlank());
        modes.add(showPlan);
        JMenu defaults = new JMenu("Mode for new sessions");
        ButtonGroup defaultGroup = new ButtonGroup();
        for (ExecutionMode m : ExecutionMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(m.label(),
                    ConfigManager.getInstance().getConfig().getDefaultExecutionMode() == m);
            item.addActionListener(e -> {
                IdeConfig cfg = ConfigManager.getInstance().getConfig();
                cfg.setDefaultExecutionMode(m);
                ConfigManager.getInstance().saveConfig(cfg);
            });
            defaultGroup.add(item);
            defaults.add(item);
        }
        modes.add(defaults);
        if (active != null && !active.getAllowedTools().isEmpty()) {
            modes.add(menuItem("Forget \"always allow\" choices (" + String.join(", ", active.getAllowedTools()) + ")", () -> {
                active.getAllowedTools().clear();
                SessionManager.getInstance().autoSaveCurrentProjectSessions();
            }));
        }
        menu.add(modes);

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
        menu.add(menuItem("Import sessions from Claude / Codex / Gemini…", () -> ideActions.openExternalSessionImport()));
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
        reg.register(new SlashCommand("plan", "Plan mode: read and search only, then propose a plan: /plan [request]", null,
                "builtin", args -> switchModeAndSend(ExecutionMode.PLAN, args)));
        reg.register(new SlashCommand("manual", "Manual mode: ask before each tool that changes something: /manual [request]",
                null, "builtin", args -> switchModeAndSend(ExecutionMode.MANUAL, args)));
        reg.register(new SlashCommand("auto", "Auto mode: run tools without asking: /auto [request]", null, "builtin",
                args -> switchModeAndSend(ExecutionMode.AUTO, args)));
        reg.register(new SlashCommand("mode", "Show or set the execution mode: /mode [plan|manual|auto]", null, "builtin", args -> {
            ExecutionMode m = ExecutionMode.parse(args);
            if (m != null) {
                setExecutionMode(m);
            } else {
                AgentSession s = SessionManager.getInstance().getActiveSession();
                appendSystemBubble("Execution mode: " + (s != null ? s.getExecutionMode().label() : "Auto")
                        + "\n/plan · /manual · /auto, or Shift+Tab in the prompt box.");
            }
        }));
        reg.register(new SlashCommand("show-plan", "Open the session's latest implementation plan in its window", null,
                "builtin", args -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) showPlanWindow(s.getId());
        }));
        reg.register(new SlashCommand("copy", "Copy the last answer (or /copy all for the conversation as Markdown)", null,
                "builtin", args -> {
            boolean all = args != null && args.strip().equalsIgnoreCase("all");
            String text = all ? conversationMarkdown() : lastAnswer();
            if (text == null || text.isBlank()) {
                appendSystemBubble("Nothing to copy yet.");
            } else {
                copyToClipboard(text);
                Toast.info(this, all ? "Conversation copied as Markdown" : "Last answer copied");
            }
        }));
        reg.register(new SlashCommand("fork", "Fork the current session: /fork [name]", null, "builtin", args -> {
            AgentSession s = SessionManager.getInstance().getActiveSession();
            if (s != null) SessionManager.getInstance().forkSession(s.getId(), s.getMessages().size(), args);
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
        reg.register(new SlashCommand("mcp", "List connected MCP servers and commands: /mcp [server]", null, "builtin", args -> {
            List<McpServerConfig> configs = McpManager.getInstance().getServerConfigs();
            if (configs.isEmpty()) {
                appendSystemBubble("No MCP servers configured. Open MCP Settings (Ctrl+Shift+P) or import from other agents.");
                return;
            }
            StringBuilder sb = new StringBuilder("Model Context Protocol (MCP) Servers:\n");
            for (McpServerConfig sc : configs) {
                boolean connected = McpManager.getInstance().isConnected(sc.getName());
                var tools = McpManager.getInstance().getDiscoveredTools(sc.getName());
                var prompts = McpManager.getInstance().getDiscoveredPrompts(sc.getName());
                sb.append(connected ? "🟢 " : "⚪ ").append(sc.getName())
                        .append(" [").append(sc.getTransport()).append("] — ")
                        .append(connected ? "connected" : (sc.isEnabled() ? "connecting/stopped" : "disabled"))
                        .append(" (").append(tools.size()).append(" tools, ").append(prompts.size()).append(" prompts)\n");
                if (connected && (!args.isBlank() && sc.getName().equalsIgnoreCase(args.trim()) || args.isBlank())) {
                    for (var t : tools) {
                        sb.append("    /").append(sc.getName()).append(":").append(t.getRawToolName())
                                .append(" — ").append(t.getDescription().split("\n")[0]).append("\n");
                    }
                    for (var p : prompts) {
                        sb.append("    /").append(sc.getName()).append(":").append(p.name())
                                .append(" — ").append(p.description()).append("\n");
                    }
                }
            }
            appendSystemBubble(sb.toString().stripTrailing());
        }));
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
        /** The message as written (Markdown for answers), for "Copy message". */
        private String rawText;

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

        public String getRawText() {
            return rawText;
        }

        public void setRawText(String rawText) {
            this.rawText = rawText;
        }
    }
}



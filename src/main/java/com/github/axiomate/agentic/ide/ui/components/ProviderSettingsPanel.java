package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.config.*;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Settings configuration panel for AI Providers.
 * Supports multiple ANTHROPIC providers, multiple GEMINI providers, multiple OPENAI providers,
 * or multiple CUSTOM/Local providers with individual endpoint URLs, API keys, models,
 * task-based routing, and context compression limits.
 */
public class ProviderSettingsPanel extends JPanel {

    private final IdeConfig config;
    private final JComboBox<String> providerSelectorCombo;

    // Provider fields
    private final JTextField nameField;
    private final JComboBox<String> typeCombo;
    private final JCheckBox providerEnabledCheck;
    private final JTextField baseUrlField;
    private final JPasswordField apiKeyField;
    private final JComboBox<String> defaultModelCombo;
    private final DefaultTableModel modelsTableModel;
    private final JTable modelsTable;

    // Routing controls
    private final JCheckBox autoRoutingCheck;
    private final Map<TaskType, JComboBox<String>> taskRoutingCombos = new EnumMap<>(TaskType.class);

    // Context compression controls
    private final JSpinner compressionThresholdSpinner;

    // File mentions (@) controls
    private final JCheckBox fileMentionsCheck;
    private final JTextField mentionTriggerField;

    // In-memory working copy of providers
    private final Map<String, ProviderConfig> workingProviders = new LinkedHashMap<>();
    private String currentSelectedProviderId = "ANTHROPIC";
    private boolean updatingUi = false;

    public ProviderSettingsPanel() {
        this.config = ConfigManager.getInstance().getConfig();

        // Deep copy existing providers into working map
        for (Map.Entry<String, ProviderConfig> entry : config.getProviders().entrySet()) {
            workingProviders.put(entry.getKey(), copyProvider(entry.getValue()));
        }

        if (!workingProviders.isEmpty()) {
            currentSelectedProviderId = workingProviders.keySet().iterator().next();
        }

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        // Create main tabbed sub-panel: "Provider Configuration" & "Task-Based Model Routing"
        JTabbedPane subTabbedPane = new JTabbedPane();

        // 1. Providers Tab
        JPanel providersTab = new JPanel(new BorderLayout(8, 8));
        providersTab.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Provider Header Selector & Management Toolbar
        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        selectorPanel.setBorder(new CompoundBorder(new LineBorder(new Color(60, 60, 65), 1), new EmptyBorder(6, 10, 6, 10)));
        selectorPanel.setBackground(new Color(36, 38, 44));

        JLabel selectLabel = new JLabel("Provider:");
        selectLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        selectorPanel.add(selectLabel);

        providerSelectorCombo = new JComboBox<>();
        providerSelectorCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        providerSelectorCombo.setPreferredSize(new Dimension(240, 24));
        providerSelectorCombo.addActionListener(e -> {
            if (updatingUi) return;
            saveCurrentProviderFieldsToWorkingMap();
            String selected = (String) providerSelectorCombo.getSelectedItem();
            if (selected != null && workingProviders.containsKey(selected)) {
                currentSelectedProviderId = selected;
                loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
            }
        });
        selectorPanel.add(providerSelectorCombo);

        JButton addProviderBtn = new JButton("+ Add Provider");
        addProviderBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        addProviderBtn.setToolTipText("Add a new provider instance (e.g. second Anthropic or Gemini endpoint)");
        addProviderBtn.addActionListener(e -> showAddProviderDialog());
        selectorPanel.add(addProviderBtn);

        JButton cloneProviderBtn = new JButton("📋 Duplicate");
        cloneProviderBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        cloneProviderBtn.setToolTipText("Clone selected provider with a new ID");
        cloneProviderBtn.addActionListener(e -> duplicateCurrentProvider());
        selectorPanel.add(cloneProviderBtn);

        JButton removeProviderBtn = new JButton("🗑 Remove");
        removeProviderBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        removeProviderBtn.addActionListener(e -> removeCurrentProvider());
        selectorPanel.add(removeProviderBtn);

        providerEnabledCheck = new JCheckBox("Enabled");
        providerEnabledCheck.setFont(new Font("SansSerif", Font.BOLD, 12));
        selectorPanel.add(Box.createHorizontalStrut(10));
        selectorPanel.add(providerEnabledCheck);

        providersTab.add(selectorPanel, BorderLayout.NORTH);

        // Provider Details Panel (URL, API Key, Default Model, Model Table)
        JPanel detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        detailsPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        JPanel credentialsPanel = new JPanel(new GridBagLayout());
        credentialsPanel.setBorder(new CompoundBorder(
                new TitledBorder("Provider Identity & Credentials"),
                new EmptyBorder(8, 10, 8, 10)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Provider Display Name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.25;
        JLabel nameLabel = new JLabel("Provider Name:");
        nameLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(nameLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.75;
        nameField = new JTextField(35);
        nameField.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(nameField, gbc);

        // Provider Type
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.25;
        JLabel typeLabel = new JLabel("Provider Type / Protocol:");
        typeLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(typeLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.75;
        typeCombo = new JComboBox<>(new String[]{"ANTHROPIC", "OPENAI", "GEMINI", "CUSTOM", "MOCK"});
        typeCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(typeCombo, gbc);

        // Base URL
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.25;
        JLabel urlLabel = new JLabel("API Base URL:");
        urlLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(urlLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.75;
        baseUrlField = new JTextField(35);
        baseUrlField.setFont(new Font("Consolas", Font.PLAIN, 12));
        credentialsPanel.add(baseUrlField, gbc);

        // API Key
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.25;
        JLabel keyLabel = new JLabel("API Key / Token:");
        keyLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(keyLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 0.75;
        apiKeyField = new JPasswordField(35);
        credentialsPanel.add(apiKeyField, gbc);

        // Default Model
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.25;
        JLabel defModelLabel = new JLabel("Default Model:");
        defModelLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(defModelLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 4; gbc.weightx = 0.75;
        defaultModelCombo = new JComboBox<>();
        defaultModelCombo.setEditable(true);
        credentialsPanel.add(defaultModelCombo, gbc);

        detailsPanel.add(credentialsPanel);
        detailsPanel.add(Box.createVerticalStrut(10));

        // Models Table Panel
        JPanel modelsPanel = new JPanel(new BorderLayout(6, 6));
        modelsPanel.setBorder(new CompoundBorder(
                new TitledBorder("Supported Models for this Provider"),
                new EmptyBorder(6, 8, 6, 8)));

        String[] columnNames = {"Model ID", "Display Name", "Max Context Tokens", "Max Output", "Capabilities / Tags"};
        modelsTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        modelsTable = new JTable(modelsTableModel);
        modelsTable.setRowHeight(22);
        modelsTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        modelsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane tableScroll = new JScrollPane(modelsTable);
        tableScroll.setPreferredSize(new Dimension(500, 160));
        modelsPanel.add(tableScroll, BorderLayout.CENTER);

        // Model Table action buttons
        JPanel tableBtnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        JButton addModelBtn = new JButton("+ Add Model");
        addModelBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        addModelBtn.addActionListener(e -> showAddModelDialog());

        JButton removeModelBtn = new JButton("- Remove Model");
        removeModelBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        removeModelBtn.addActionListener(e -> removeSelectedModel());

        tableBtnBar.add(addModelBtn);
        tableBtnBar.add(removeModelBtn);
        modelsPanel.add(tableBtnBar, BorderLayout.SOUTH);

        detailsPanel.add(modelsPanel);
        providersTab.add(new JScrollPane(detailsPanel), BorderLayout.CENTER);

        subTabbedPane.addTab("AI Providers & URLs", providersTab);

        // 2. Task Routing Tab
        JPanel routingTab = new JPanel(new BorderLayout(10, 10));
        routingTab.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel routingHeader = new JPanel(new BorderLayout(8, 4));
        routingHeader.setBorder(new CompoundBorder(new LineBorder(new Color(60, 60, 65), 1), new EmptyBorder(8, 10, 8, 10)));
        routingHeader.setBackground(new Color(36, 38, 44));

        autoRoutingCheck = new JCheckBox("Enable Autonomous Task-Based Model Routing", config.isAutoRoutingEnabled());
        autoRoutingCheck.setFont(new Font("SansSerif", Font.BOLD, 12));
        JLabel routeDesc = new JLabel("Automatically delegates tasks (Refactor, Tests, Explain, Debug, Tools) to the best configured provider and model.");
        routeDesc.setFont(new Font("SansSerif", Font.PLAIN, 11));
        routeDesc.setForeground(Color.LIGHT_GRAY);

        routingHeader.add(autoRoutingCheck, BorderLayout.NORTH);
        routingHeader.add(routeDesc, BorderLayout.SOUTH);
        routingTab.add(routingHeader, BorderLayout.NORTH);

        // Routing Grid Panel
        JPanel gridPanel = new JPanel(new GridBagLayout());
        gridPanel.setBorder(new CompoundBorder(new TitledBorder("Task Type to Provider:Model Routing"), new EmptyBorder(10, 12, 10, 12)));
        GridBagConstraints rGbc = new GridBagConstraints();
        rGbc.insets = new Insets(6, 6, 6, 6);
        rGbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        for (TaskType type : TaskType.values()) {
            rGbc.gridx = 0; rGbc.gridy = row; rGbc.weightx = 0.35;
            JLabel taskLabel = new JLabel(formatTaskName(type) + ":");
            taskLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
            gridPanel.add(taskLabel, rGbc);

            rGbc.gridx = 1; rGbc.gridy = row; rGbc.weightx = 0.65;
            JComboBox<String> routeCombo = new JComboBox<>(buildAllProviderModelOptions());
            String configuredRoute = config.getTaskRouting().get(type);
            if (configuredRoute != null) {
                routeCombo.setSelectedItem(configuredRoute);
            }
            taskRoutingCombos.put(type, routeCombo);
            gridPanel.add(routeCombo, rGbc);

            row++;
        }

        // Context Compression Setting Panel
        JPanel compPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        compPanel.setBorder(new CompoundBorder(new TitledBorder("Context Limit & Auto-Compression"), new EmptyBorder(6, 10, 6, 10)));
        JLabel compLabel = new JLabel("Auto-compress conversation history when token consumption reaches:");
        compLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        compPanel.add(compLabel);

        int initialPct = (int) Math.round(config.getAutoCompressionThreshold() * 100);
        compressionThresholdSpinner = new JSpinner(new SpinnerNumberModel(initialPct, 50, 99, 1));
        compPanel.add(compressionThresholdSpinner);
        compPanel.add(new JLabel("% of maximum model context (Default: 95%)"));

        // File Mentions (@) Setting Panel
        JPanel mentionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        mentionPanel.setBorder(new CompoundBorder(new TitledBorder("File Mentions (@) & Workspace Context"), new EmptyBorder(6, 10, 6, 10)));
        fileMentionsCheck = new JCheckBox("Enable '@' File Mentions & Auto-Context Injection in Agent Chat", config.isFileMentionsEnabled());
        fileMentionsCheck.setFont(new Font("SansSerif", Font.BOLD, 12));
        mentionPanel.add(fileMentionsCheck);

        mentionPanel.add(Box.createHorizontalStrut(10));
        mentionPanel.add(new JLabel("Trigger Symbol:"));
        mentionTriggerField = new JTextField(config.getMentionTriggerChar(), 3);
        mentionPanel.add(mentionTriggerField);

        JLabel mentionHelp = new JLabel("(Lists workspace files when typed, injects file content into prompt)");
        mentionHelp.setFont(new Font("SansSerif", Font.PLAIN, 11));
        mentionHelp.setForeground(Color.GRAY);
        mentionPanel.add(mentionHelp);

        JPanel routingCenter = new JPanel();
        routingCenter.setLayout(new BoxLayout(routingCenter, BoxLayout.Y_AXIS));
        routingCenter.add(gridPanel);
        routingCenter.add(Box.createVerticalStrut(10));
        routingCenter.add(compPanel);
        routingCenter.add(Box.createVerticalStrut(10));
        routingCenter.add(mentionPanel);

        routingTab.add(new JScrollPane(routingCenter), BorderLayout.CENTER);
        subTabbedPane.addTab("Task-Based Model Routing & Limits", routingTab);

        add(subTabbedPane, BorderLayout.CENTER);

        // Initial load
        refreshProviderSelectorCombo();
        loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
    }

    private void refreshProviderSelectorCombo() {
        updatingUi = true;
        try {
            providerSelectorCombo.removeAllItems();
            for (String pId : workingProviders.keySet()) {
                providerSelectorCombo.addItem(pId);
            }
            if (workingProviders.containsKey(currentSelectedProviderId)) {
                providerSelectorCombo.setSelectedItem(currentSelectedProviderId);
            } else if (!workingProviders.isEmpty()) {
                currentSelectedProviderId = workingProviders.keySet().iterator().next();
                providerSelectorCombo.setSelectedItem(currentSelectedProviderId);
            }
        } finally {
            updatingUi = false;
        }
    }

    private String formatTaskName(TaskType type) {
        return switch (type) {
            case GENERAL -> "General Coding & Chat";
            case EXPLAIN -> "Code Explanation & Architecture";
            case REFACTOR -> "Code Refactoring & Modernization";
            case GENERATE_TESTS -> "Unit Test Generation (JUnit 5)";
            case DEBUG_FIX -> "Bug Fixing & Diagnostics";
            case TERMINAL_TOOL -> "Terminal & MCP Tool Execution";
        };
    }

    private String[] buildAllProviderModelOptions() {
        List<String> options = new ArrayList<>();
        for (Map.Entry<String, ProviderConfig> provEntry : workingProviders.entrySet()) {
            String pId = provEntry.getKey();
            for (ModelDefinition m : provEntry.getValue().getModels()) {
                options.add(pId + ":" + m.getId());
            }
        }
        return options.toArray(new String[0]);
    }

    private void refreshRoutingCombos() {
        String[] options = buildAllProviderModelOptions();
        for (Map.Entry<TaskType, JComboBox<String>> entry : taskRoutingCombos.entrySet()) {
            Object selected = entry.getValue().getSelectedItem();
            entry.getValue().setModel(new DefaultComboBoxModel<>(options));
            if (selected != null) {
                entry.getValue().setSelectedItem(selected);
            }
        }
    }

    private void loadProviderFieldsFromWorkingMap(String providerId) {
        ProviderConfig prov = workingProviders.get(providerId);
        if (prov == null) return;

        updatingUi = true;
        try {
            nameField.setText(prov.getName());
            typeCombo.setSelectedItem(prov.getProviderType());
            providerEnabledCheck.setSelected(prov.isEnabled());
            baseUrlField.setText(prov.getBaseUrl());
            apiKeyField.setText(prov.getApiKey());

            defaultModelCombo.removeAllItems();
            modelsTableModel.setRowCount(0);

            for (ModelDefinition m : prov.getModels()) {
                defaultModelCombo.addItem(m.getId());
                modelsTableModel.addRow(new Object[]{
                        m.getId(),
                        m.getDisplayName(),
                        String.format("%,d", m.getMaxContextTokens()),
                        String.format("%,d", m.getMaxOutputTokens()),
                        String.join(", ", m.getTags())
                });
            }
            defaultModelCombo.setSelectedItem(prov.getDefaultModel());
        } finally {
            updatingUi = false;
        }
    }

    private void saveCurrentProviderFieldsToWorkingMap() {
        ProviderConfig prov = workingProviders.get(currentSelectedProviderId);
        if (prov != null) {
            prov.setName(nameField.getText().trim());
            prov.setProviderType((String) typeCombo.getSelectedItem());
            prov.setEnabled(providerEnabledCheck.isSelected());
            prov.setBaseUrl(baseUrlField.getText().trim());
            prov.setApiKey(new String(apiKeyField.getPassword()).trim());
            Object def = defaultModelCombo.getSelectedItem();
            if (def != null) {
                prov.setDefaultModel(def.toString().trim());
            }
        }
    }

    private void showAddProviderDialog() {
        JTextField idField = new JTextField("anthropic-work", 18);
        JTextField nameInputField = new JTextField("Anthropic Work Account", 18);
        JComboBox<String> typeChoice = new JComboBox<>(new String[]{"ANTHROPIC", "OPENAI", "GEMINI", "CUSTOM"});
        JTextField urlField = new JTextField("https://api.anthropic.com/v1", 25);
        JPasswordField keyField = new JPasswordField(25);

        typeChoice.addActionListener(e -> {
            String selectedType = (String) typeChoice.getSelectedItem();
            switch (selectedType) {
                case "ANTHROPIC" -> {
                    urlField.setText("https://api.anthropic.com/v1");
                    if (nameInputField.getText().contains("Account")) nameInputField.setText("Anthropic Account " + (workingProviders.size() + 1));
                }
                case "GEMINI" -> {
                    urlField.setText("https://generativelanguage.googleapis.com/v1beta");
                    if (nameInputField.getText().contains("Account")) nameInputField.setText("Google Gemini " + (workingProviders.size() + 1));
                }
                case "OPENAI" -> {
                    urlField.setText("https://api.openai.com/v1");
                    if (nameInputField.getText().contains("Account")) nameInputField.setText("OpenAI Endpoint " + (workingProviders.size() + 1));
                }
                case "CUSTOM" -> {
                    urlField.setText("http://localhost:11434/v1");
                    if (nameInputField.getText().contains("Account")) nameInputField.setText("Local Ollama " + (workingProviders.size() + 1));
                }
            }
        });

        JPanel panel = new JPanel(new GridLayout(5, 2, 6, 6));
        panel.add(new JLabel("Provider Unique ID:"));
        panel.add(idField);
        panel.add(new JLabel("Provider Type / Protocol:"));
        panel.add(typeChoice);
        panel.add(new JLabel("Display Name:"));
        panel.add(nameInputField);
        panel.add(new JLabel("API Base URL:"));
        panel.add(urlField);
        panel.add(new JLabel("API Key:"));
        panel.add(keyField);

        int result = JOptionPane.showConfirmDialog(this, panel, "Add New Configurable AI Provider",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION && !idField.getText().isBlank()) {
            String pId = idField.getText().trim().toUpperCase().replace(" ", "_");
            String pType = (String) typeChoice.getSelectedItem();
            String pName = nameInputField.getText().isBlank() ? pId : nameInputField.getText().trim();
            String pUrl = urlField.getText().trim();
            String pKey = new String(keyField.getPassword()).trim();

            List<ModelDefinition> defaultModels = createDefaultModelsForType(pType);
            String defModel = defaultModels.isEmpty() ? "default" : defaultModels.get(0).getId();

            ProviderConfig newProv = new ProviderConfig(pId, pType, pName, pUrl, defModel, defaultModels);
            newProv.setApiKey(pKey);

            workingProviders.put(pId, newProv);
            currentSelectedProviderId = pId;

            refreshProviderSelectorCombo();
            loadProviderFieldsFromWorkingMap(pId);
            refreshRoutingCombos();
        }
    }

    private void duplicateCurrentProvider() {
        ProviderConfig current = workingProviders.get(currentSelectedProviderId);
        if (current == null) return;

        String newId = current.getId() + "_COPY";
        int count = 2;
        while (workingProviders.containsKey(newId)) {
            newId = current.getId() + "_COPY_" + (count++);
        }

        ProviderConfig clone = copyProvider(current);
        clone.setId(newId);
        clone.setName(current.getName() + " (Copy)");

        workingProviders.put(newId, clone);
        currentSelectedProviderId = newId;

        refreshProviderSelectorCombo();
        loadProviderFieldsFromWorkingMap(newId);
        refreshRoutingCombos();
    }

    private void removeCurrentProvider() {
        if (workingProviders.size() <= 1) {
            JOptionPane.showMessageDialog(this, "At least one provider must remain configured.", "Cannot Remove", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to remove provider '" + currentSelectedProviderId + "'?",
                "Remove Provider", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            workingProviders.remove(currentSelectedProviderId);
            currentSelectedProviderId = workingProviders.keySet().iterator().next();
            refreshProviderSelectorCombo();
            loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
            refreshRoutingCombos();
        }
    }

    private List<ModelDefinition> createDefaultModelsForType(String type) {
        return switch (type) {
            case "ANTHROPIC" -> List.of(
                    new ModelDefinition("claude-3-7-sonnet", "Claude 3.7 Sonnet", 200_000, 8_192, List.of("reasoning", "coding")),
                    new ModelDefinition("claude-3-5-sonnet", "Claude 3.5 Sonnet", 200_000, 8_192, List.of("coding", "tools")),
                    new ModelDefinition("claude-3-5-haiku", "Claude 3.5 Haiku", 200_000, 4_096, List.of("fast"))
            );
            case "GEMINI" -> List.of(
                    new ModelDefinition("gemini-2.0-flash", "Gemini 2.0 Flash", 1_000_000, 8_192, List.of("fast", "tools")),
                    new ModelDefinition("gemini-1.5-pro", "Gemini 1.5 Pro", 2_000_000, 8_192, List.of("massive-context")),
                    new ModelDefinition("gemini-1.5-flash", "Gemini 1.5 Flash", 1_000_000, 8_192, List.of("fast"))
            );
            case "OPENAI" -> List.of(
                    new ModelDefinition("gpt-4o", "GPT-4o", 128_000, 4_096, List.of("coding", "general")),
                    new ModelDefinition("gpt-4o-mini", "GPT-4o Mini", 128_000, 4_096, List.of("fast")),
                    new ModelDefinition("o1", "o1 (Reasoning)", 200_000, 32_768, List.of("reasoning")),
                    new ModelDefinition("o3-mini", "o3-mini", 200_000, 16_384, List.of("reasoning"))
            );
            default -> List.of(
                    new ModelDefinition("qwen2.5-coder", "Qwen 2.5 Coder", 32_768, 4_096, List.of("coding", "local")),
                    new ModelDefinition("llama3.2", "Llama 3.2", 8_192, 2_048, List.of("local"))
            );
        };
    }

    private void showAddModelDialog() {
        JTextField idField = new JTextField(18);
        JTextField nameModalField = new JTextField(18);
        JSpinner ctxSpinner = new JSpinner(new SpinnerNumberModel(128_000, 1_000, 2_000_000, 1_000));
        JSpinner outSpinner = new JSpinner(new SpinnerNumberModel(4_096, 512, 64_000, 512));
        JTextField tagsField = new JTextField("coding, tools", 18);

        JPanel panel = new JPanel(new GridLayout(5, 2, 6, 6));
        panel.add(new JLabel("Model ID (e.g. claude-3-7-sonnet):"));
        panel.add(idField);
        panel.add(new JLabel("Display Name:"));
        panel.add(nameModalField);
        panel.add(new JLabel("Max Context Tokens:"));
        panel.add(ctxSpinner);
        panel.add(new JLabel("Max Output Tokens:"));
        panel.add(outSpinner);
        panel.add(new JLabel("Tags / Capabilities:"));
        panel.add(tagsField);

        int result = JOptionPane.showConfirmDialog(this, panel, "Add New Model Definition",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION && !idField.getText().isBlank()) {
            String mId = idField.getText().trim();
            String mName = nameModalField.getText().isBlank() ? mId : nameModalField.getText().trim();
            int maxCtx = (Integer) ctxSpinner.getValue();
            int maxOut = (Integer) outSpinner.getValue();
            List<String> tags = Arrays.stream(tagsField.getText().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            ModelDefinition modelDef = new ModelDefinition(mId, mName, maxCtx, maxOut, tags);
            ProviderConfig current = workingProviders.get(currentSelectedProviderId);
            if (current != null) {
                current.getModels().removeIf(m -> m.getId().equalsIgnoreCase(mId));
                current.getModels().add(modelDef);
                loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
                refreshRoutingCombos();
            }
        }
    }

    private void removeSelectedModel() {
        int selectedRow = modelsTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this, "Please select a model row to remove.", "Selection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String modelId = (String) modelsTableModel.getValueAt(selectedRow, 0);
        ProviderConfig current = workingProviders.get(currentSelectedProviderId);
        if (current != null && current.getModels().size() > 1) {
            current.getModels().removeIf(m -> m.getId().equals(modelId));
            loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
            refreshRoutingCombos();
        } else {
            JOptionPane.showMessageDialog(this, "A provider must retain at least one model.", "Cannot Remove", JOptionPane.WARNING_MESSAGE);
        }
    }

    private ProviderConfig copyProvider(ProviderConfig original) {
        List<ModelDefinition> models = new ArrayList<>();
        if (original.getModels() != null) {
            for (ModelDefinition m : original.getModels()) {
                models.add(new ModelDefinition(m.getId(), m.getDisplayName(), m.getMaxContextTokens(), m.getMaxOutputTokens(), new ArrayList<>(m.getTags())));
            }
        }
        ProviderConfig copy = new ProviderConfig(
                original.getId(),
                original.getProviderType(),
                original.getName(),
                original.getBaseUrl(),
                original.getDefaultModel(),
                models
        );
        copy.setApiKey(original.getApiKey());
        copy.setEnabled(original.isEnabled());
        return copy;
    }

    /**
     * Applies changes from the panel directly into IdeConfig.
     */
    public void applyToConfig(IdeConfig targetConfig) {
        saveCurrentProviderFieldsToWorkingMap();

        targetConfig.setProviders(workingProviders);
        targetConfig.setAutoRoutingEnabled(autoRoutingCheck.isSelected());

        double threshold = ((Integer) compressionThresholdSpinner.getValue()) / 100.0;
        targetConfig.setAutoCompressionThreshold(threshold);

        Map<TaskType, String> routings = new EnumMap<>(TaskType.class);
        for (Map.Entry<TaskType, JComboBox<String>> entry : taskRoutingCombos.entrySet()) {
            Object selected = entry.getValue().getSelectedItem();
            if (selected != null) {
                routings.put(entry.getKey(), selected.toString());
            }
        }
        targetConfig.setTaskRouting(routings);
        targetConfig.setFileMentionsEnabled(fileMentionsCheck.isSelected());
        String trig = mentionTriggerField.getText().trim();
        targetConfig.setMentionTriggerChar(trig.isEmpty() ? "@" : trig);
    }
}


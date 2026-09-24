package com.github.agentforge.agentic.ide.ui.components;

import com.github.agentforge.agentic.ide.config.*;
import com.github.agentforge.agentic.ide.ui.util.UIUtils;

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
 * Settings configuration panel for AI Providers (Anthropic, OpenAI, Google Gemini, Custom/Local),
 * configurable endpoint URLs, multiple models per provider, automatic task-based routing,
 * and context compression limits.
 */
public class ProviderSettingsPanel extends JPanel {

    private final IdeConfig config;
    private final JComboBox<String> providerSelectorCombo;

    // Provider fields
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

    // In-memory working copy of providers
    private final Map<String, ProviderConfig> workingProviders = new LinkedHashMap<>();
    private String currentSelectedProviderId = "ANTHROPIC";

    public ProviderSettingsPanel() {
        this.config = ConfigManager.getInstance().getConfig();

        // Deep copy existing providers into working map
        for (Map.Entry<String, ProviderConfig> entry : config.getProviders().entrySet()) {
            workingProviders.put(entry.getKey(), copyProvider(entry.getValue()));
        }

        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        // Create main tabbed sub-panel: "Provider Configuration" & "Task-Based Model Routing"
        JTabbedPane subTabbedPane = new JTabbedPane();

        // 1. Providers Tab
        JPanel providersTab = new JPanel(new BorderLayout(8, 8));
        providersTab.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Provider Header Selector
        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        selectorPanel.setBorder(new CompoundBorder(new LineBorder(new Color(60, 60, 65), 1), new EmptyBorder(6, 10, 6, 10)));
        selectorPanel.setBackground(new Color(36, 38, 44));

        JLabel selectLabel = new JLabel("Select Provider to Configure:");
        selectLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        selectorPanel.add(selectLabel);

        providerSelectorCombo = new JComboBox<>(new String[]{"ANTHROPIC", "OPENAI", "GEMINI", "CUSTOM", "MOCK"});
        providerSelectorCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        providerSelectorCombo.setSelectedItem("ANTHROPIC");
        providerSelectorCombo.addActionListener(e -> {
            saveCurrentProviderFieldsToWorkingMap();
            currentSelectedProviderId = (String) providerSelectorCombo.getSelectedItem();
            loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
        });
        selectorPanel.add(providerSelectorCombo);

        providerEnabledCheck = new JCheckBox("Provider Enabled");
        providerEnabledCheck.setFont(new Font("SansSerif", Font.BOLD, 12));
        selectorPanel.add(Box.createHorizontalStrut(15));
        selectorPanel.add(providerEnabledCheck);

        providersTab.add(selectorPanel, BorderLayout.NORTH);

        // Provider Details Panel (URL, API Key, Default Model, Model Table)
        JPanel detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        detailsPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        JPanel credentialsPanel = new JPanel(new GridBagLayout());
        credentialsPanel.setBorder(new CompoundBorder(
                new TitledBorder("Endpoint URL & Credentials"),
                new EmptyBorder(8, 10, 8, 10)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Base URL
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.25;
        JLabel urlLabel = new JLabel("API Base URL:");
        urlLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(urlLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.75;
        baseUrlField = new JTextField(35);
        baseUrlField.setFont(new Font("Consolas", Font.PLAIN, 12));
        credentialsPanel.add(baseUrlField, gbc);

        // API Key
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.25;
        JLabel keyLabel = new JLabel("API Key / Token:");
        keyLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(keyLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.75;
        apiKeyField = new JPasswordField(35);
        credentialsPanel.add(apiKeyField, gbc);

        // Default Model
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.25;
        JLabel defModelLabel = new JLabel("Default Model:");
        defModelLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        credentialsPanel.add(defModelLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.75;
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
        JLabel routeDesc = new JLabel("Automatically delegates tasks (Refactor, Tests, Explain, Debug) to the best configured provider and model.");
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

        JPanel routingCenter = new JPanel();
        routingCenter.setLayout(new BoxLayout(routingCenter, BoxLayout.Y_AXIS));
        routingCenter.add(gridPanel);
        routingCenter.add(Box.createVerticalStrut(10));
        routingCenter.add(compPanel);

        routingTab.add(new JScrollPane(routingCenter), BorderLayout.CENTER);
        subTabbedPane.addTab("Task-Based Model Routing & Limits", routingTab);

        add(subTabbedPane, BorderLayout.CENTER);

        // Initial load
        loadProviderFieldsFromWorkingMap(currentSelectedProviderId);
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

    private void loadProviderFieldsFromWorkingMap(String providerId) {
        ProviderConfig prov = workingProviders.get(providerId);
        if (prov == null) return;

        providerEnabledCheck.setSelected(prov.isEnabled());
        baseUrlField.setText(prov.getBaseUrl());
        apiKeyField.setText(prov.getApiKey());

        // Update default model combo and table
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
    }

    private void saveCurrentProviderFieldsToWorkingMap() {
        ProviderConfig prov = workingProviders.get(currentSelectedProviderId);
        if (prov != null) {
            prov.setEnabled(providerEnabledCheck.isSelected());
            prov.setBaseUrl(baseUrlField.getText().trim());
            prov.setApiKey(new String(apiKeyField.getPassword()).trim());
            Object def = defaultModelCombo.getSelectedItem();
            if (def != null) {
                prov.setDefaultModel(def.toString().trim());
            }
        }
    }

    private void showAddModelDialog() {
        JTextField idField = new JTextField(18);
        JTextField nameField = new JTextField(18);
        JSpinner ctxSpinner = new JSpinner(new SpinnerNumberModel(128_000, 1_000, 2_000_000, 1_000));
        JSpinner outSpinner = new JSpinner(new SpinnerNumberModel(4_096, 512, 64_000, 512));
        JTextField tagsField = new JTextField("coding, tools", 18);

        JPanel panel = new JPanel(new GridLayout(5, 2, 6, 6));
        panel.add(new JLabel("Model ID (e.g. claude-3-7-sonnet):"));
        panel.add(idField);
        panel.add(new JLabel("Display Name:"));
        panel.add(nameField);
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
            String mName = nameField.getText().isBlank() ? mId : nameField.getText().trim();
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
    }
}

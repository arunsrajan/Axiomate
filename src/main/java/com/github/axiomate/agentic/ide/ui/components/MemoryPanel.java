package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.memory.AgentMemoryStore;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Dedicated GUI panel for browsing, searching, adding, and importing/exporting Agentic AI memories.
 */
public class MemoryPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(MemoryPanel.class);

    private final DefaultListModel<MemoryItem> listModel = new DefaultListModel<>();
    private final JList<MemoryItem> memoryList = new JList<>(listModel);
    private final JComboBox<String> typeFilterCombo;
    private final JComboBox<String> scopeFilterCombo;
    private final JTextField searchField;
    private com.github.axiomate.agentic.ide.ui.IdeActions ideActions = com.github.axiomate.agentic.ide.ui.IdeActions.NONE;

    private final JLabel titleField = new JLabel("Select a memory to view details");
    private final JLabel metaField = new JLabel("");
    private final JTextArea contentArea = new JTextArea();

    public MemoryPanel() {
        setLayout(new BorderLayout());

        // 1. Top Toolbar
        JPanel toolBar = new JPanel(new BorderLayout(8, 0));
        toolBar.setBorder(new EmptyBorder(6, 10, 6, 10));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        searchField = new JTextField(16);
        searchField.putClientProperty("JTextField.placeholderText", "Search memory...");
        searchField.addActionListener(e -> refreshList());
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshList(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshList(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshList(); }
        });

        typeFilterCombo = new JComboBox<>(new String[]{"All Types", "PROJECT_RULE", "LONG_TERM", "EPISODIC", "WORKING"});
        typeFilterCombo.addActionListener(e -> refreshList());

        scopeFilterCombo = new JComboBox<>(new String[]{"This project + global", "All projects", "Global only"});
        scopeFilterCombo.setToolTipText("Imported project rules apply only to their own project");
        scopeFilterCombo.addActionListener(e -> refreshList());

        leftActions.add(new JLabel("Type:"));
        leftActions.add(typeFilterCombo);
        leftActions.add(scopeFilterCombo);
        leftActions.add(searchField);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        JButton agentImportBtn = UIUtils.createPillButton("Import from Agents…", null, UIUtils.ACCENT_COLOR, Color.WHITE);
        agentImportBtn.setToolTipText("Import CLAUDE.md, AGENTS.md, Cursor/Antigravity/Windsurf rules, Gemini memories…");
        agentImportBtn.addActionListener(e -> ideActions.openMemoryImport());

        JButton agentExportBtn = new JButton("Export to Agents…");
        agentExportBtn.setToolTipText("Write memories into Claude Code, Codex, Cursor, Antigravity… instruction files");
        agentExportBtn.addActionListener(e -> ideActions.openMemoryExport());

        JButton moreBtn = new JButton("⋯");
        moreBtn.setToolTipText("Import or export memories as files");
        moreBtn.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem importFile = new JMenuItem("Import from JSON / Markdown file or folder…");
            importFile.addActionListener(ev -> importMemories());
            JMenuItem exportFile = new JMenuItem("Export all memories to JSON…");
            exportFile.addActionListener(ev -> exportMemories());
            menu.add(importFile);
            menu.add(exportFile);
            menu.show(moreBtn, 0, moreBtn.getHeight());
        });

        JButton addBtn = new JButton("Add...");
        addBtn.addActionListener(e -> addNewMemory());

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> deleteSelected());

        rightActions.add(agentImportBtn);
        rightActions.add(agentExportBtn);
        rightActions.add(addBtn);
        rightActions.add(deleteBtn);
        rightActions.add(moreBtn);

        toolBar.add(leftActions, BorderLayout.WEST);
        toolBar.add(rightActions, BorderLayout.EAST);
        add(toolBar, BorderLayout.NORTH);

        // 2. Main Split: List on Left, Detail Viewer on Right
        memoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        memoryList.setCellRenderer(new MemoryListCellRenderer());
        memoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetails(memoryList.getSelectedValue());
            }
        });

        JScrollPane listScroll = new JScrollPane(memoryList);
        listScroll.setBorder(new LineBorder(UIUtils.borderColor(), 1));

        JPanel detailPanel = new JPanel(new BorderLayout(6, 6));
        detailPanel.setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel detailHeader = new JPanel(new GridLayout(2, 1, 2, 2));
        titleField.setFont(new Font("SansSerif", Font.BOLD, 13));
        metaField.setFont(new Font("SansSerif", Font.PLAIN, 11));
        metaField.setForeground(Color.GRAY);
        detailHeader.add(titleField);
        detailHeader.add(metaField);

        contentArea.setEditable(false);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setFont(UIUtils.getEditorFont(12));
        contentArea.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane contentScroll = new JScrollPane(contentArea);
        contentScroll.setBorder(new LineBorder(UIUtils.borderColor(), 1));

        detailPanel.add(detailHeader, BorderLayout.NORTH);
        detailPanel.add(contentScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailPanel);
        splitPane.setResizeWeight(0.35);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        // Listen for memory updates from any thread
        MemoryManager.getInstance().addChangeListener(this::refreshList);

        // Initial populate
        refreshList();
    }

    public void setIdeActions(com.github.axiomate.agentic.ide.ui.IdeActions actions) {
        this.ideActions = actions != null ? actions : com.github.axiomate.agentic.ide.ui.IdeActions.NONE;
    }

    public void refreshList() {
        SwingUtilities.invokeLater(() -> {
            listModel.clear();
            AgentMemoryStore store = MemoryManager.getInstance().getMemoryStore();
            String query = searchField.getText().trim();
            String filter = (String) typeFilterCombo.getSelectedItem();
            int scopeMode = scopeFilterCombo.getSelectedIndex();
            String activeProject = MemoryManager.getInstance().getActiveProjectPath();

            List<MemoryItem> items;
            if (!query.isEmpty()) {
                items = store.search(query, 50);
            } else {
                items = store.getAllMemories();
            }

            for (MemoryItem item : items) {
                boolean typeOk = "All Types".equals(filter) || item.getType().name().equalsIgnoreCase(filter);
                boolean scopeOk = switch (scopeMode) {
                    case 1 -> true;
                    case 2 -> item.getProjectPath() == null;
                    default -> item.isVisibleInScope(activeProject);
                };
                if (typeOk && scopeOk) {
                    listModel.addElement(item);
                }
            }

            if (!listModel.isEmpty() && memoryList.getSelectedIndex() < 0) {
                memoryList.setSelectedIndex(0);
            }
        });
    }

    private void updateDetails(MemoryItem item) {
        if (item != null) {
            titleField.setText(item.getTitle());
            String scope = item.getProjectPath() != null ? "Project: " + new File(item.getProjectPath()).getName() : "Global";
            String source = item.getSource() != null ? " | Source: " + item.getSource().replaceFirst("^[^|]*\\|", "") : "";
            metaField.setText("Type: " + item.getType() + " | " + scope + " | Tags: " + String.join(", ", item.getTags())
                    + " | Updated: " + item.getTimestamp() + source);
            metaField.setToolTipText(item.getSource());
            contentArea.setText(item.getContent());
        } else {
            titleField.setText("Select a memory to view details");
            metaField.setText("");
            contentArea.setText("");
        }
    }

    public void importMemories() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import All Memory into IDE");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            try {
                int count = MemoryManager.getInstance().importAllMemories(selected);
                JOptionPane.showMessageDialog(this,
                        "Successfully imported " + count + " memory item(s) into the IDE!",
                        "Memory Import Complete", JOptionPane.INFORMATION_MESSAGE);
                refreshList();
            } catch (IOException ex) {
                log.error("Failed to import memories", ex);
                JOptionPane.showMessageDialog(this,
                        "Error importing memory: " + ex.getMessage(),
                        "Import Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void exportMemories() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export All Memories");
        chooser.setSelectedFile(new File("agent_memory_export.json"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            try {
                MemoryManager.getInstance().exportAllMemories(target);
                JOptionPane.showMessageDialog(this,
                        "Successfully exported memories to " + target.getName(),
                        "Export Successful", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "Error exporting memories: " + ex.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void addNewMemory() {
        JTextField titleIn = new JTextField();
        JComboBox<MemoryType> typeIn = new JComboBox<>(MemoryType.values());
        JTextField tagsIn = new JTextField("project, custom");
        JTextArea contentIn = new JTextArea(5, 20);

        JPanel form = new JPanel(new GridLayout(4, 2, 4, 4));
        form.add(new JLabel("Title:"));
        form.add(titleIn);
        form.add(new JLabel("Type:"));
        form.add(typeIn);
        form.add(new JLabel("Tags (comma separated):"));
        form.add(tagsIn);
        form.add(new JLabel("Content:"));

        JPanel outer = new JPanel(new BorderLayout(4, 4));
        outer.add(form, BorderLayout.NORTH);
        outer.add(new JScrollPane(contentIn), BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(this, outer, "Add New Agentic Memory", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            String title = titleIn.getText().trim();
            String content = contentIn.getText().trim();
            if (!title.isEmpty() && !content.isEmpty()) {
                List<String> tags = List.of(tagsIn.getText().split("\\s*,\\s*"));
                MemoryItem item = new MemoryItem((MemoryType) typeIn.getSelectedItem(), title, content, tags);
                MemoryManager.getInstance().addMemory(item);
                refreshList();
            }
        }
    }

    private void deleteSelected() {
        MemoryItem selected = memoryList.getSelectedValue();
        if (selected == null) return;

        int res = JOptionPane.showConfirmDialog(this,
                "Delete memory: " + selected.getTitle() + "?", "Delete Memory", JOptionPane.YES_NO_OPTION);
        if (res == JOptionPane.YES_OPTION) {
            MemoryManager.getInstance().removeMemory(selected.getId());
            refreshList();
        }
    }

    private static class MemoryListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof MemoryItem item) {
                setText("[" + item.getType() + "] " + item.getTitle());
                if (item.getType() == MemoryType.PROJECT_RULE) {
                    setIcon(UIUtils.createSparkleIcon(12, UIUtils.ACCENT_PURPLE));
                } else if (item.getType() == MemoryType.EPISODIC) {
                    setIcon(UIUtils.createPlayIcon(12, UIUtils.WARNING_COLOR));
                } else {
                    setIcon(UIUtils.createFileIcon(12, UIUtils.ACCENT_COLOR));
                }
            }
            return this;
        }
    }
}


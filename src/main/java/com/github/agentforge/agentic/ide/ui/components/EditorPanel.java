package com.github.agentforge.agentic.ide.ui.components;

import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.ui.util.UIUtils;
import com.github.agentforge.agentic.ide.util.ProjectManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Tabbed code editor supporting syntax highlighting, code folding, and dirty state management.
 */
public class EditorPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(EditorPanel.class);

    private final JTabbedPane tabbedPane;
    private final Map<Component, File> tabFileMap = new HashMap<>();
    private final Map<Component, RSyntaxTextArea> tabEditorMap = new HashMap<>();
    private final Map<Component, Boolean> dirtyMap = new HashMap<>();

    public EditorPanel() {
        setLayout(new BorderLayout());
        tabbedPane = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);

        tabbedPane.addChangeListener(e -> {
            Component selected = tabbedPane.getSelectedComponent();
            if (selected != null) {
                File file = tabFileMap.get(selected);
                ProjectManager.getInstance().setActiveFile(file);
            } else {
                ProjectManager.getInstance().setActiveFile(null);
            }
        });

        add(tabbedPane, BorderLayout.CENTER);
        ProjectManager.getInstance().addFileContentListener(this::reloadOrUpdateFile);
    }

    public void reloadOrUpdateFile(File file, String newContent) {
        if (file == null) return;
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component c = tabbedPane.getComponentAt(i);
                File openFile = tabFileMap.get(c);
                if (openFile != null && openFile.getAbsolutePath().equalsIgnoreCase(file.getAbsolutePath())) {
                    RSyntaxTextArea area = tabEditorMap.get(c);
                    if (area != null && !area.getText().equals(newContent)) {
                        int pos = Math.min(area.getCaretPosition(), newContent.length());
                        area.setText(newContent);
                        area.setCaretPosition(pos);
                        markDirty(c, false);
                    }
                    return;
                }
            }
        });
    }

    public void openFile(File file) {
        if (file == null || !file.exists() || file.isDirectory()) {
            return;
        }

        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            File open = tabFileMap.get(c);
            if (open != null && open.equals(file)) {
                tabbedPane.setSelectedIndex(i);
                return;
            }
        }

        try {
            String content = Files.readString(file.toPath());
            createTab(file.getName(), content, file);
            ProjectManager.getInstance().setActiveFile(file);
        } catch (IOException e) {
            log.error("Failed to read file: {}", file.getAbsolutePath(), e);
            JOptionPane.showMessageDialog(this, "Could not read file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void newFile(String title, String initialContent) {
        createTab(title, initialContent != null ? initialContent : "", null);
    }

    private void createTab(String title, String content, File file) {
        RSyntaxTextArea textArea = new RSyntaxTextArea(25, 80);
        textArea.setText(content);
        textArea.setCaretPosition(0);
        textArea.setCodeFoldingEnabled(true);
        textArea.setAntiAliasingEnabled(true);
        textArea.setBracketMatchingEnabled(true);
        textArea.setAnimateBracketMatching(true);

        IdeConfig config = ConfigManager.getInstance().getConfig();
        textArea.setFont(UIUtils.getEditorFont(config.getFontSize()));

        String syntaxStyle = getSyntaxStyleForFile(file != null ? file.getName() : title);
        textArea.setSyntaxEditingStyle(syntaxStyle);

        try {
            Theme theme = Theme.load(getClass().getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
            theme.apply(textArea);
        } catch (Exception e) {
            textArea.setBackground(new Color(30, 30, 30));
            textArea.setForeground(new Color(220, 220, 220));
            textArea.setCaretColor(Color.WHITE);
        }

        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        scrollPane.setFoldIndicatorEnabled(true);
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setBorder(null);

        tabFileMap.put(scrollPane, file);
        tabEditorMap.put(scrollPane, textArea);
        dirtyMap.put(scrollPane, false);

        tabbedPane.addTab(title, scrollPane);
        int index = tabbedPane.indexOfComponent(scrollPane);
        tabbedPane.setTabComponentAt(index, createTabHeader(title, scrollPane));
        tabbedPane.setSelectedComponent(scrollPane);

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { markDirty(scrollPane, true); }
            @Override
            public void removeUpdate(DocumentEvent e) { markDirty(scrollPane, true); }
            @Override
            public void changedUpdate(DocumentEvent e) { markDirty(scrollPane, true); }
        });
    }

    private JPanel createTabHeader(String title, Component tabComponent) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        JButton closeBtn = new JButton("×");
        closeBtn.setFont(new Font("Arial", Font.BOLD, 14));
        closeBtn.setMargin(new Insets(0, 4, 0, 4));
        closeBtn.setBorder(new EmptyBorder(0, 4, 0, 0));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        closeBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                closeBtn.setForeground(UIUtils.ERROR_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                closeBtn.setForeground(null);
            }
        });

        closeBtn.addActionListener(e -> closeTab(tabComponent));

        panel.add(titleLabel);
        panel.add(closeBtn);
        return panel;
    }

    private void markDirty(Component tabComponent, boolean dirty) {
        dirtyMap.put(tabComponent, dirty);
        int index = tabbedPane.indexOfComponent(tabComponent);
        if (index >= 0) {
            Component header = tabbedPane.getTabComponentAt(index);
            if (header instanceof JPanel p && p.getComponentCount() > 0 && p.getComponent(0) instanceof JLabel l) {
                File f = tabFileMap.get(tabComponent);
                String name = (f != null) ? f.getName() : "Untitled";
                l.setText(name + (dirty ? " *" : ""));
            }
        }
    }

    public void closeActiveTab() {
        Component selected = tabbedPane.getSelectedComponent();
        if (selected != null) {
            closeTab(selected);
        }
    }

    public void closeTab(Component tabComponent) {
        Boolean isDirty = dirtyMap.get(tabComponent);
        if (Boolean.TRUE.equals(isDirty)) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Save changes before closing?", "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                saveTab(tabComponent);
            } else if (result == JOptionPane.CANCEL_OPTION) {
                return;
            }
        }
        tabFileMap.remove(tabComponent);
        tabEditorMap.remove(tabComponent);
        dirtyMap.remove(tabComponent);
        tabbedPane.remove(tabComponent);
    }

    public boolean saveActiveFile() {
        Component selected = tabbedPane.getSelectedComponent();
        if (selected != null) {
            return saveTab(selected);
        }
        return false;
    }

    public boolean saveActiveFileAs() {
        Component selected = tabbedPane.getSelectedComponent();
        if (selected == null) return false;

        JFileChooser chooser = new JFileChooser(ProjectManager.getInstance().getCurrentProjectDirectory());
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            tabFileMap.put(selected, target);
            return saveTab(selected);
        }
        return false;
    }

    private boolean saveTab(Component tabComponent) {
        File file = tabFileMap.get(tabComponent);
        RSyntaxTextArea editor = tabEditorMap.get(tabComponent);
        if (editor == null) return false;

        if (file == null) {
            JFileChooser chooser = new JFileChooser(ProjectManager.getInstance().getCurrentProjectDirectory());
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                file = chooser.getSelectedFile();
                tabFileMap.put(tabComponent, file);
            } else {
                return false;
            }
        }

        try {
            Files.writeString(file.toPath(), editor.getText());
            markDirty(tabComponent, false);
            ProjectManager.getInstance().setActiveFile(file);
            log.info("Saved file {}", file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("Failed to save file: {}", file.getAbsolutePath(), e);
            JOptionPane.showMessageDialog(this, "Save failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    public RSyntaxTextArea getActiveEditor() {
        Component selected = tabbedPane.getSelectedComponent();
        return tabEditorMap.get(selected);
    }

    public File getActiveFile() {
        Component selected = tabbedPane.getSelectedComponent();
        return tabFileMap.get(selected);
    }

    public String getActiveText() {
        RSyntaxTextArea editor = getActiveEditor();
        return editor != null ? editor.getText() : "";
    }

    public String getSelectedText() {
        RSyntaxTextArea editor = getActiveEditor();
        return editor != null ? editor.getSelectedText() : "";
    }

    public void replaceActiveContent(String text) {
        RSyntaxTextArea editor = getActiveEditor();
        if (editor != null && text != null) {
            editor.setText(text);
            editor.setCaretPosition(0);
        }
    }

    public void setEditorFontSize(int size) {
        Font font = UIUtils.getEditorFont(size);
        for (RSyntaxTextArea editor : tabEditorMap.values()) {
            editor.setFont(font);
        }
    }

    private String getSyntaxStyleForFile(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".java")) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if (lower.endsWith(".py")) return SyntaxConstants.SYNTAX_STYLE_PYTHON;
        if (lower.endsWith(".js")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        if (lower.endsWith(".ts")) return SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
        if (lower.endsWith(".json")) return SyntaxConstants.SYNTAX_STYLE_JSON;
        if (lower.endsWith(".xml")) return SyntaxConstants.SYNTAX_STYLE_XML;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (lower.endsWith(".css")) return SyntaxConstants.SYNTAX_STYLE_CSS;
        if (lower.endsWith(".sql")) return SyntaxConstants.SYNTAX_STYLE_SQL;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) return SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH;
        if (lower.endsWith(".sh")) return SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }
}

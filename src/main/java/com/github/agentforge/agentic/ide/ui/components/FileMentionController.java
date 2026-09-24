package com.github.agentforge.agentic.ide.ui.components;

import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.ui.util.UIUtils;
import com.github.agentforge.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller for '@' file mentions in the AI Agent chat window.
 * Displays a searchable, keyboard-navigable popup list of workspace files
 * when '@' is typed, and injects mentioned file contents into the agent prompt context.
 */
public class FileMentionController {

    private static final Logger log = LoggerFactory.getLogger(FileMentionController.class);

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "target", "build", "node_modules",
            ".idea", ".vscode", ".settings", ".agentforge-ide", ".agentic-ide",
            "bin", "obj", ".gradle"
    );

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".class", ".jar", ".war", ".ear", ".exe", ".dll", ".so", ".dylib",
            ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg", ".gif", ".ico",
            ".mp3", ".mp4", ".pdf", ".iso", ".pyc"
    );

    private static final int MAX_FILE_CHARS = 30_000;
    private static final int MAX_WORKSPACE_FILES = 1_000;

    private final JTextArea inputArea;
    private final JPopupMenu popupMenu;
    private final DefaultListModel<String> listModel;
    private final JList<String> fileList;
    private final JScrollPane listScroll;

    private List<String> cachedWorkspaceFiles = new ArrayList<>();
    private long lastCacheTime = 0;
    private static final long CACHE_TTL_MS = 5_000;

    private int mentionStartOffset = -1;

    public FileMentionController(JTextArea inputArea) {
        this.inputArea = inputArea;

        this.popupMenu = new JPopupMenu();
        this.popupMenu.setBorder(new LineBorder(new Color(75, 80, 95), 1));
        this.popupMenu.setFocusable(false);

        this.listModel = new DefaultListModel<>();
        this.fileList = new JList<>(listModel);
        this.fileList.setFont(new Font("Consolas", Font.PLAIN, 12));
        this.fileList.setBackground(new Color(30, 32, 38));
        this.fileList.setForeground(new Color(225, 230, 240));
        this.fileList.setSelectionBackground(UIUtils.ACCENT_COLOR);
        this.fileList.setSelectionForeground(Color.WHITE);
        this.fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.fileList.setCellRenderer(new MentionCellRenderer());

        this.fileList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    applySelectedMention();
                }
            }
        });

        this.listScroll = new JScrollPane(fileList);
        this.listScroll.setPreferredSize(new Dimension(320, 160));
        this.listScroll.setBorder(null);

        JPanel contentPanel = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel(" Select File to Mention");
        headerLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
        headerLabel.setForeground(Color.GRAY);
        headerLabel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        contentPanel.add(headerLabel, BorderLayout.NORTH);
        contentPanel.add(listScroll, BorderLayout.CENTER);

        this.popupMenu.add(contentPanel);

        setupInputListeners();
    }

    private void setupInputListeners() {
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                checkMentionTrigger();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkMentionTrigger();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                checkMentionTrigger();
            }
        });

        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!popupMenu.isVisible()) {
                    return;
                }

                int code = e.getKeyCode();
                if (code == KeyEvent.VK_DOWN) {
                    e.consume();
                    int cur = fileList.getSelectedIndex();
                    int next = Math.min(listModel.getSize() - 1, cur + 1);
                    fileList.setSelectedIndex(next);
                    fileList.ensureIndexIsVisible(next);
                } else if (code == KeyEvent.VK_UP) {
                    e.consume();
                    int cur = fileList.getSelectedIndex();
                    int prev = Math.max(0, cur - 1);
                    fileList.setSelectedIndex(prev);
                    fileList.ensureIndexIsVisible(prev);
                } else if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB) {
                    if (!e.isControlDown() && !e.isMetaDown()) {
                        e.consume();
                        applySelectedMention();
                    }
                } else if (code == KeyEvent.VK_ESCAPE) {
                    e.consume();
                    popupMenu.setVisible(false);
                }
            }
        });
    }

    private void checkMentionTrigger() {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        if (!config.isFileMentionsEnabled()) {
            if (popupMenu.isVisible()) {
                popupMenu.setVisible(false);
            }
            return;
        }

        SwingUtilities.invokeLater(() -> {
            int caret = inputArea.getCaretPosition();
            String text = inputArea.getText();
            String trigger = config.getMentionTriggerChar();
            if (trigger == null || trigger.isEmpty()) trigger = "@";

            MentionQuery mq = extractQueryAtCaret(text, caret, trigger);
            if (mq == null) {
                if (popupMenu.isVisible()) {
                    popupMenu.setVisible(false);
                }
                mentionStartOffset = -1;
                return;
            }

            mentionStartOffset = mq.startOffset;
            List<String> matches = filterWorkspaceFiles(mq.query);
            if (matches.isEmpty()) {
                popupMenu.setVisible(false);
                return;
            }

            listModel.clear();
            for (String f : matches) {
                listModel.addElement(f);
            }
            fileList.setSelectedIndex(0);

            showPopupAtCaret(caret);
        });
    }

    private void showPopupAtCaret(int caretPosition) {
        try {
            Rectangle2D r = inputArea.modelToView2D(caretPosition);
            if (r != null) {
                popupMenu.show(inputArea, (int) r.getX(), (int) (r.getY() + r.getHeight()));
                inputArea.requestFocusInWindow();
            }
        } catch (Exception e) {
            log.debug("Could not position file mention popup: {}", e.getMessage());
        }
    }

    private void applySelectedMention() {
        String selected = fileList.getSelectedValue();
        if (selected == null || mentionStartOffset < 0) {
            popupMenu.setVisible(false);
            return;
        }

        IdeConfig config = ConfigManager.getInstance().getConfig();
        String trigger = config.getMentionTriggerChar();
        if (trigger == null || trigger.isEmpty()) trigger = "@";

        String text = inputArea.getText();
        int caret = inputArea.getCaretPosition();

        if (mentionStartOffset <= text.length() && caret >= mentionStartOffset) {
            String replacement = trigger + selected + " ";
            String prefix = text.substring(0, mentionStartOffset);
            String suffix = (caret <= text.length()) ? text.substring(caret) : "";
            inputArea.setText(prefix + replacement + suffix);
            inputArea.setCaretPosition(prefix.length() + replacement.length());
        }

        popupMenu.setVisible(false);
        mentionStartOffset = -1;
    }

    public List<String> filterWorkspaceFiles(String query) {
        List<String> allFiles = getWorkspaceFiles();
        if (query == null || query.isBlank()) {
            return allFiles.stream().limit(25).toList();
        }

        String lowerQuery = query.toLowerCase().trim();
        List<String> startsWith = new ArrayList<>();
        List<String> contains = new ArrayList<>();

        for (String file : allFiles) {
            String fileName = new File(file).getName().toLowerCase();
            String lowerPath = file.toLowerCase();

            if (fileName.startsWith(lowerQuery) || lowerPath.startsWith(lowerQuery)) {
                startsWith.add(file);
            } else if (lowerPath.contains(lowerQuery)) {
                contains.add(file);
            }
        }

        List<String> result = new ArrayList<>(startsWith);
        result.addAll(contains);
        return result.stream().distinct().limit(25).toList();
    }

    public synchronized List<String> getWorkspaceFiles() {
        long now = System.currentTimeMillis();
        if (cachedWorkspaceFiles != null && !cachedWorkspaceFiles.isEmpty() && (now - lastCacheTime < CACHE_TTL_MS)) {
            return cachedWorkspaceFiles;
        }

        File root = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (root == null || !root.exists() || !root.isDirectory()) {
            return Collections.emptyList();
        }

        List<String> files = new ArrayList<>();
        scanDirectory(root, root, files);
        Collections.sort(files);

        cachedWorkspaceFiles = files;
        lastCacheTime = now;
        return files;
    }

    private void scanDirectory(File root, File current, List<String> collector) {
        if (collector.size() >= MAX_WORKSPACE_FILES) return;

        File[] list = current.listFiles();
        if (list == null) return;

        for (File f : list) {
            if (collector.size() >= MAX_WORKSPACE_FILES) return;

            String name = f.getName();
            if (f.isDirectory()) {
                if (!IGNORED_DIRS.contains(name) && !name.startsWith(".")) {
                    scanDirectory(root, f, collector);
                }
            } else {
                if (!name.startsWith(".") && isAcceptableTextFile(name)) {
                    String relPath = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                    collector.add(relPath);
                }
            }
        }
    }

    private boolean isAcceptableTextFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return true;
        String ext = fileName.substring(dot).toLowerCase();
        return !IGNORED_EXTENSIONS.contains(ext);
    }

    // Static helper for query extraction
    public static MentionQuery extractQueryAtCaret(String text, int caret, String trigger) {
        if (text == null || caret < 0 || caret > text.length()) return null;

        int lineStart = text.lastIndexOf('\n', Math.max(0, caret - 1));
        lineStart = (lineStart < 0) ? 0 : lineStart + 1;
        String currentLine = text.substring(lineStart, caret);

        int triggerIdx = currentLine.lastIndexOf(trigger);
        if (triggerIdx < 0) return null;

        // Ensure trigger is either at start of line or preceded by whitespace
        if (triggerIdx > 0 && !Character.isWhitespace(currentLine.charAt(triggerIdx - 1))) {
            return null;
        }

        String query = currentLine.substring(triggerIdx + trigger.length());
        // If there's whitespace inside the query, mention is closed/completed
        if (query.contains(" ") || query.contains("\t")) {
            return null;
        }

        int globalStart = lineStart + triggerIdx;
        return new MentionQuery(query, globalStart);
    }

    /**
     * Scans a prompt string for '@' mentions, resolves each matching file in the workspace,
     * reads its contents, and formats them into an attached context block.
     */
    public static String buildMentionedFilesContext(String prompt, File projectDir) {
        if (prompt == null || prompt.isBlank() || projectDir == null || !projectDir.exists()) {
            return "";
        }

        IdeConfig config = ConfigManager.getInstance().getConfig();
        String trigger = Pattern.quote(config.getMentionTriggerChar());
        Pattern pattern = Pattern.compile("(?:^|\\s)" + trigger + "([\\w\\.\\-\\/\\\\]+)");
        Matcher matcher = pattern.matcher(prompt);

        Set<String> matchedTokens = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token != null && !token.isBlank()) {
                matchedTokens.add(token.trim().replace('\\', '/'));
            }
        }

        if (matchedTokens.isEmpty()) {
            return "";
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (String token : matchedTokens) {
            File targetFile = resolveMentionedFile(projectDir, token);
            if (targetFile != null && targetFile.exists() && targetFile.isFile()) {
                try {
                    String content = Files.readString(targetFile.toPath());
                    if (content.length() > MAX_FILE_CHARS) {
                        content = content.substring(0, MAX_FILE_CHARS) + "\n... [Truncated due to context limit]";
                    }
                    String relPath = projectDir.toPath().relativize(targetFile.toPath()).toString().replace('\\', '/');
                    contextBuilder.append("\n\n--- Mentioned File Context: ").append(relPath).append(" ---\n");
                    contextBuilder.append(content).append("\n");
                    log.info("Injected @ file mention context for: {}", relPath);
                } catch (IOException e) {
                    log.warn("Failed to read mentioned file {}: {}", targetFile.getAbsolutePath(), e.getMessage());
                }
            }
        }

        return contextBuilder.toString().trim();
    }

    public static File resolveMentionedFile(File projectDir, String token) {
        if (projectDir == null || token == null || token.isBlank()) return null;

        // 1. Direct path check
        File direct = new File(projectDir, token);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }

        // 2. Absolute path check
        File abs = new File(token);
        if (abs.isAbsolute() && abs.exists() && abs.isFile()) {
            return abs;
        }

        // 3. Recursive lookup by relative path or file name
        String targetName = new File(token).getName();
        return findFileByNameRecursive(projectDir, targetName, 0);
    }

    private static File findFileByNameRecursive(File current, String targetName, int depth) {
        if (depth > 6 || current == null || !current.exists()) return null;

        File[] children = current.listFiles();
        if (children == null) return null;

        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory()) {
                if (!IGNORED_DIRS.contains(name) && !name.startsWith(".")) {
                    File found = findFileByNameRecursive(child, targetName, depth + 1);
                    if (found != null) return found;
                }
            } else if (name.equalsIgnoreCase(targetName)) {
                return child;
            }
        }
        return null;
    }

    public static class MentionQuery {
        public final String query;
        public final int startOffset;

        public MentionQuery(String query, int startOffset) {
            this.query = query;
            this.startOffset = startOffset;
        }
    }

    private static class MentionCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            if (value instanceof String path) {
                String fileName = new File(path).getName();
                label.setText("📄 " + fileName + "  (" + path + ")");
            }
            return label;
        }
    }
}

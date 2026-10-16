package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * A syntax-highlighted code block component rendering fenced code blocks (```lang ... ```)
 * within AI agent response messages.
 */
public class MarkdownCodePanel extends JPanel {

    private final MarkdownTableParser.CodeBlockSegment segment;
    private final RSyntaxTextArea codeArea;
    private final JScrollPane scrollPane;
    private final JLabel langLabel;
    private final JButton copyBtn;
    private final JTextArea rawArea;

    public MarkdownCodePanel(MarkdownTableParser.CodeBlockSegment segment) {
        super(new BorderLayout(0, 0));
        this.segment = segment;
        setOpaque(false);

        // Hidden raw markdown for accessibility and test traversal
        rawArea = new JTextArea(segment.rawMarkdown());
        rawArea.setVisible(false);
        add(rawArea, BorderLayout.SOUTH);

        // 1. Configure RSyntaxTextArea
        codeArea = new RSyntaxTextArea(segment.code());
        codeArea.setEditable(false);
        codeArea.setHighlightCurrentLine(false);
        codeArea.setAnimateBracketMatching(false);
        codeArea.setCodeFoldingEnabled(false);
        codeArea.setSyntaxEditingStyle(mapSyntaxStyle(segment.language()));
        codeArea.setFont(UIUtils.getEditorFont(12));
        codeArea.setMargin(new Insets(6, 12, 6, 12));

        // 2. Configure ScrollPane
        scrollPane = new JScrollPane(codeArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);

        int lineCount = Math.max(codeArea.getLineCount(), 1);
        int fontH = Math.max(codeArea.getFontMetrics(codeArea.getFont()).getHeight(), 18);
        int targetHeight = Math.min(lineCount * fontH + 16, 380);
        scrollPane.setPreferredSize(new Dimension(scrollPane.getPreferredSize().width, targetHeight));
        scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 400));

        // 3. Top Header Bar with Language badge and Copy action
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setOpaque(true);

        String langName = (segment.language() == null || segment.language().isBlank())
                ? "CODE" : segment.language().trim().toUpperCase();
        langLabel = new JLabel(langName);
        langLabel.setFont(UIUtils.uiFont(Font.BOLD, 10.5f));

        copyBtn = new JButton("Copy");
        copyBtn.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        copyBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        copyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyBtn.setToolTipText("Copy code snippet to clipboard");
        copyBtn.addActionListener(e -> copyCodeToClipboard(segment.code()));

        topBar.add(langLabel, BorderLayout.WEST);
        topBar.add(copyBtn, BorderLayout.EAST);

        // 4. Main Card Container
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(true);
        card.add(topBar, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);

        // Apply theme styling initially and on theme change
        Runnable themeApplier = () -> applyThemeStyles(card, topBar);
        themeApplier.run();
        UIUtils.addThemeListener(() -> SwingUtilities.invokeLater(themeApplier));

        add(card, BorderLayout.CENTER);
        setBorder(new EmptyBorder(4, 0, 4, 0));
    }

    private void applyThemeStyles(JPanel card, JPanel topBar) {
        Color border = UIUtils.borderColor();
        Color bg = UIUtils.surface(2);
        Color fg = UIUtils.foreground();
        Color muted = UIUtils.mutedForeground();

        card.setBackground(bg);
        card.setBorder(new CompoundBorder(
                new MatteBorder(1, 1, 1, 1, border),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
        ));

        topBar.setBackground(UIUtils.surface(3));
        topBar.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, border),
                new EmptyBorder(4, 10, 4, 8)
        ));
        langLabel.setForeground(muted);
        copyBtn.setForeground(muted);

        String scheme = UIUtils.isDark() ? "dark.xml" : "idea.xml";
        try (var in = getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + scheme)) {
            if (in != null) Theme.load(in).apply(codeArea);
        } catch (Exception ignored) {
            codeArea.setBackground(bg);
            codeArea.setForeground(fg);
        }
        codeArea.setFont(UIUtils.getEditorFont(12));

        revalidate();
        repaint();
    }

    private void copyCodeToClipboard(String content) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
            copyBtn.setText("✓ Copied");
            copyBtn.setForeground(UIUtils.SUCCESS_COLOR);
            Timer timer = new Timer(1500, e -> {
                copyBtn.setText("Copy");
                copyBtn.setForeground(UIUtils.mutedForeground());
            });
            timer.setRepeats(false);
            timer.start();
        } catch (Exception ignored) {
        }
    }

    public static String mapSyntaxStyle(String lang) {
        if (lang == null) return SyntaxConstants.SYNTAX_STYLE_NONE;
        String lower = lang.toLowerCase().trim();
        return switch (lower) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "py", "python" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "js", "javascript" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "ts", "typescript" -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
            case "json" -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case "xml" -> SyntaxConstants.SYNTAX_STYLE_XML;
            case "html", "htm" -> SyntaxConstants.SYNTAX_STYLE_HTML;
            case "css" -> SyntaxConstants.SYNTAX_STYLE_CSS;
            case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
            case "sh", "bash", "shell", "zsh" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            case "bat", "cmd", "powershell", "ps1" -> SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH;
            case "yaml", "yml" -> SyntaxConstants.SYNTAX_STYLE_YAML;
            case "c", "cpp", "c++" -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
            case "cs", "csharp", "c#" -> SyntaxConstants.SYNTAX_STYLE_CSHARP;
            case "go", "golang" -> SyntaxConstants.SYNTAX_STYLE_GO;
            case "rust", "rs" -> SyntaxConstants.SYNTAX_STYLE_RUST;
            case "kt", "kotlin" -> SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            case "scala" -> SyntaxConstants.SYNTAX_STYLE_SCALA;
            case "groovy" -> SyntaxConstants.SYNTAX_STYLE_GROOVY;
            case "ruby", "rb" -> SyntaxConstants.SYNTAX_STYLE_RUBY;
            case "php" -> SyntaxConstants.SYNTAX_STYLE_PHP;
            case "md", "markdown" -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }

    public RSyntaxTextArea getCodeArea() {
        return codeArea;
    }

    public MarkdownTableParser.CodeBlockSegment getSegment() {
        return segment;
    }

    public String getRawMarkdown() {
        return segment.rawMarkdown();
    }
}

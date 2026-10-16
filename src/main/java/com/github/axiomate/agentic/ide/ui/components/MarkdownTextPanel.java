package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rich text panel rendering Markdown typography (headings, bold, italic, inline code,
 * bullet lists, and paragraphs) within AI agent response messages.
 */
public class MarkdownTextPanel extends JPanel {

    private final String rawMarkdown;
    private final JEditorPane editorPane;
    private final JTextArea rawArea;

    public MarkdownTextPanel(String markdown) {
        super(new BorderLayout());
        this.rawMarkdown = markdown != null ? markdown : "";
        setOpaque(false);

        // Hidden raw text for accessibility and test inspection
        rawArea = new JTextArea(this.rawMarkdown);
        rawArea.setVisible(false);
        add(rawArea, BorderLayout.SOUTH);

        editorPane = new JEditorPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
        editorPane.setContentType("text/html");
        editorPane.setEditable(false);
        editorPane.setOpaque(false);
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        renderHtmlContent();
        add(editorPane, BorderLayout.CENTER);

        UIUtils.addThemeListener(() -> SwingUtilities.invokeLater(this::renderHtmlContent));
    }

    private void renderHtmlContent() {
        Font font = UIUtils.uiFont(Font.PLAIN, 12.5f);
        editorPane.setFont(font);

        Color fg = UIUtils.foreground();
        Color muted = UIUtils.mutedForeground();
        boolean dark = UIUtils.isDark();
        String fgHex = toHex(fg);
        String mutedHex = toHex(muted);
        String codeBgHex = dark ? "#383838" : "#e8e8e8";
        String codeFgHex = dark ? "#f0f0f0" : "#202020";
        String borderColor = toHex(UIUtils.borderColor());

        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet sheet = kit.getStyleSheet();
        sheet.addRule("body { font-family: " + font.getFamily() + ", sans-serif; font-size: " + font.getSize() + "pt; color: " + fgHex + "; margin: 0; padding: 0; }");
        sheet.addRule("h1 { font-size: 15pt; font-weight: bold; color: " + fgHex + "; margin-top: 10px; margin-bottom: 4px; padding-bottom: 2px; }");
        sheet.addRule("h2 { font-size: 13.5pt; font-weight: bold; color: " + fgHex + "; margin-top: 10px; margin-bottom: 4px; border-bottom: 1px solid " + borderColor + "; padding-bottom: 2px; }");
        sheet.addRule("h3 { font-size: 12pt; font-weight: bold; color: " + fgHex + "; margin-top: 8px; margin-bottom: 3px; }");
        sheet.addRule("code { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: 11pt; background-color: " + codeBgHex + "; color: " + codeFgHex + "; padding: 1px 3px; }");
        sheet.addRule("b, strong { font-weight: bold; color: " + fgHex + "; }");
        sheet.addRule("i, em { font-style: italic; color: " + fgHex + "; }");
        sheet.addRule("ul { margin-top: 4px; margin-bottom: 6px; margin-left: 20px; padding: 0; }");
        sheet.addRule("li { margin-top: 2px; margin-bottom: 2px; color: " + fgHex + "; }");
        sheet.addRule("p { margin-top: 3px; margin-bottom: 4px; color: " + fgHex + "; }");
        sheet.addRule(".lead { color: " + mutedHex + "; }");

        editorPane.setEditorKit(kit);
        editorPane.setText("<html><body>" + markdownToHtmlBody(rawMarkdown, codeBgHex, codeFgHex) + "</body></html>");
        editorPane.setCaretPosition(0);
        revalidate();
        repaint();
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * Checks if a string contains Markdown typography syntax (headings, bold, italic, code, lists).
     */
    public static boolean hasMarkdownFormatting(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("##") || text.contains("# ") || text.contains("**")
                || text.contains("`") || text.contains("~~")
                || Pattern.compile("(?m)^\\s*[-*]\\s+").matcher(text).find()
                || Pattern.compile("(?m)^\\s*\\d+\\.\\s+").matcher(text).find();
    }

    /**
     * Transforms standard Markdown paragraphs, headings, bold, inline code, and lists into HTML body tags.
     */
    public static String markdownToHtmlBody(String markdown, String codeBg, String codeFg) {
        if (markdown == null || markdown.isBlank()) return "";

        String[] lines = markdown.split("\\R", -1);
        StringBuilder html = new StringBuilder();
        boolean inList = false;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();

            if (trimmed.isEmpty()) {
                if (inList) {
                    html.append("</ul>\n");
                    inList = false;
                }
                html.append("<p style='margin:4px 0;'></p>\n");
                continue;
            }

            // Headings
            if (trimmed.startsWith("#")) {
                if (inList) {
                    html.append("</ul>\n");
                    inList = false;
                }
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level <= 6 && level < trimmed.length() && trimmed.charAt(level) == ' ') {
                    String headingContent = trimmed.substring(level + 1).trim();
                    String tag = level == 1 ? "h1" : (level == 2 ? "h2" : "h3");
                    html.append('<').append(tag).append('>')
                            .append(formatInline(headingContent, codeBg, codeFg))
                            .append("</").append(tag).append(">\n");
                    continue;
                }
            }

            // Unordered List Items (- or *)
            Matcher listMatcher = Pattern.compile("^\\s*[-*]\\s+(.*)$").matcher(rawLine);
            if (listMatcher.matches()) {
                if (!inList) {
                    html.append("<ul>\n");
                    inList = true;
                }
                String itemContent = listMatcher.group(1);
                html.append("<li>").append(formatInline(itemContent, codeBg, codeFg)).append("</li>\n");
                continue;
            }

            // Regular paragraph line
            if (inList) {
                html.append("</ul>\n");
                inList = false;
            }
            html.append("<p>").append(formatInline(trimmed, codeBg, codeFg)).append("</p>\n");
        }

        if (inList) {
            html.append("</ul>\n");
        }

        return html.toString();
    }

    private static String formatInline(String text, String codeBg, String codeFg) {
        if (text == null) return "";
        // 1. Escape HTML special characters
        String res = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

        // 2. Inline code: `code`
        res = res.replaceAll("`([^`]+)`", "<code style='font-family:monospace; background-color:"
                + codeBg + "; color:" + codeFg + "; padding:1px 4px; border-radius:3px;'>$1</code>");

        // 3. Bold: **text** or __text__
        res = res.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        res = res.replaceAll("__(.+?)__", "<b>$1</b>");

        // 4. Italic: *text* (negative lookaround so we don't match bold asterisks)
        res = res.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");

        // 5. Strikethrough: ~~text~~
        res = res.replaceAll("~~([^~]+)~~", "<strike>$1</strike>");

        return res;
    }

    public JEditorPane getEditorPane() {
        return editorPane;
    }

    public String getRawMarkdown() {
        return rawMarkdown;
    }
}

package com.github.axiomate.agentic.ide.interop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight parser for the Markdown instruction files used by coding agents
 * (CLAUDE.md, AGENTS.md, GEMINI.md, Cursor .mdc rules, Antigravity/Windsurf rules...).
 * <p>
 * Extracts a simple YAML frontmatter map and splits the body into heading-delimited sections,
 * ignoring headings that appear inside fenced code blocks.
 */
public final class MarkdownDocument {

    public static final String MANAGED_START = "<!-- axiomate:memory:start -->";
    public static final String MANAGED_END = "<!-- axiomate:memory:end -->";

    /**
     * A heading-delimited section of a Markdown document.
     */
    public record Section(String title, String content, int level) {
    }

    private final Map<String, String> frontmatter;
    private final String body;

    private MarkdownDocument(Map<String, String> frontmatter, String body) {
        this.frontmatter = frontmatter;
        this.body = body;
    }

    public static MarkdownDocument parse(String raw) {
        String text = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Map<String, String> fm = new LinkedHashMap<>();
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end > 0) {
                parseFrontmatter(text.substring(4, end), fm);
                int bodyStart = text.indexOf('\n', end + 4);
                text = bodyStart < 0 ? "" : text.substring(bodyStart + 1);
            }
        }
        return new MarkdownDocument(Collections.unmodifiableMap(fm), text);
    }

    private static void parseFrontmatter(String block, Map<String, String> out) {
        String currentListKey = null;
        List<String> listValues = new ArrayList<>();
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (currentListKey != null && trimmed.startsWith("- ")) {
                listValues.add(unquote(trimmed.substring(2).trim()));
                continue;
            }
            if (currentListKey != null) {
                out.put(currentListKey, String.join(", ", listValues));
                currentListKey = null;
                listValues.clear();
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                currentListKey = key;
                continue;
            }
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> parts = new ArrayList<>();
                for (String p : value.substring(1, value.length() - 1).split(",")) {
                    String v = unquote(p.trim());
                    if (!v.isEmpty()) parts.add(v);
                }
                value = String.join(", ", parts);
            } else {
                value = unquote(value);
            }
            out.put(key, value);
        }
        if (currentListKey != null) {
            out.put(currentListKey, String.join(", ", listValues));
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    public Map<String, String> getFrontmatter() {
        return frontmatter;
    }

    public String frontmatter(String key) {
        String v = frontmatter.get(key);
        return v == null || v.isBlank() ? null : v;
    }

    public String getBody() {
        return body;
    }

    /**
     * Returns a copy of this document with any Axiomate-managed export block removed.
     */
    public MarkdownDocument withoutManagedBlock() {
        return new MarkdownDocument(frontmatter, stripManagedBlock(body));
    }

    /**
     * The first heading text in the document, or null.
     */
    public String firstHeading() {
        for (Heading h : headings(body.split("\n", -1))) {
            return h.text;
        }
        return null;
    }

    /**
     * Splits the body into sections. The split level is the shallowest heading level (1-3)
     * that occurs at least twice; content before the first split heading becomes a leading section
     * titled with the document's H1 (or {@code fallbackTitle}).
     */
    public List<Section> sections(String fallbackTitle) {
        String[] lines = body.split("\n", -1);
        List<Heading> headings = headings(lines);

        int splitLevel = -1;
        for (int level = 1; level <= 3 && splitLevel < 0; level++) {
            int count = 0;
            for (Heading h : headings) {
                if (h.level == level) count++;
            }
            if (count >= 2) splitLevel = level;
        }

        List<Section> result = new ArrayList<>();
        if (splitLevel < 0) {
            String title = headings.isEmpty() ? fallbackTitle : headings.get(0).text;
            String content = body;
            if (!headings.isEmpty() && headings.get(0).level == 1) {
                content = removeLine(lines, headings.get(0).line);
            }
            if (!content.isBlank()) {
                result.add(new Section(title, content.strip(), headings.isEmpty() ? 0 : headings.get(0).level));
            }
            return result;
        }

        List<Heading> splits = new ArrayList<>();
        for (Heading h : headings) {
            if (h.level == splitLevel) splits.add(h);
        }

        // Preamble before the first split heading
        String docTitle = fallbackTitle;
        StringBuilder preamble = new StringBuilder();
        for (int i = 0; i < splits.get(0).line; i++) {
            Heading h = headingAt(headings, i);
            if (h != null && h.level < splitLevel) {
                docTitle = h.text;
                continue;
            }
            preamble.append(lines[i]).append('\n');
        }
        if (!preamble.toString().isBlank()) {
            result.add(new Section(docTitle, preamble.toString().strip(), splitLevel - 1));
        }

        for (int s = 0; s < splits.size(); s++) {
            Heading h = splits.get(s);
            int end = (s + 1 < splits.size()) ? splits.get(s + 1).line : lines.length;
            StringBuilder sb = new StringBuilder();
            for (int i = h.line + 1; i < end; i++) {
                Heading inner = headingAt(headings, i);
                if (inner != null && inner.level < splitLevel) {
                    break; // a shallower heading ends this section
                }
                sb.append(lines[i]).append('\n');
            }
            if (!sb.toString().isBlank()) {
                result.add(new Section(h.text, sb.toString().strip(), splitLevel));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Managed block helpers
    // ---------------------------------------------------------------------

    public static boolean hasManagedBlock(String text) {
        return text != null && text.contains(MANAGED_START) && text.contains(MANAGED_END);
    }

    public static String stripManagedBlock(String text) {
        if (!hasManagedBlock(text)) return text;
        int start = text.indexOf(MANAGED_START);
        int end = text.indexOf(MANAGED_END, start);
        if (end < 0) return text;
        String before = text.substring(0, start);
        String after = text.substring(end + MANAGED_END.length());
        return (before.stripTrailing() + "\n\n" + after.stripLeading()).strip() + "\n";
    }

    /**
     * Inserts or replaces the Axiomate-managed block inside {@code existing}.
     * An empty {@code blockBody} removes the block entirely.
     */
    public static String upsertManagedBlock(String existing, String blockBody) {
        // Work in \n and write back in the file's own line endings (a Windows CLAUDE.md stays CRLF)
        boolean crlf = existing != null && existing.contains("\r\n");
        String result = upsertLf(existing == null ? "" : existing.replace("\r\n", "\n"), blockBody);
        return crlf ? result.replace("\n", "\r\n") : result;
    }

    private static String upsertLf(String base, String blockBody) {
        boolean remove = blockBody == null || blockBody.isBlank();
        String block = remove ? "" : MANAGED_START + "\n" + blockBody.strip().replace("\r\n", "\n") + "\n" + MANAGED_END + "\n";

        int start = base.indexOf(MANAGED_START);
        int endMarker = start < 0 ? -1 : base.indexOf(MANAGED_END, start);
        if (start >= 0 && endMarker >= 0) { // only a start marker followed by an end marker is our block
            int end = endMarker + MANAGED_END.length();
            String before = base.substring(0, start);
            String after = base.substring(end);
            if (after.startsWith("\n")) after = after.substring(1);
            String merged = before + block + after;
            return remove ? merged.strip() + (merged.isBlank() ? "" : "\n") : merged;
        }
        if (remove) return base;
        if (base.isBlank()) return block;
        return base.stripTrailing() + "\n\n" + block;
    }

    // ---------------------------------------------------------------------

    private record Heading(int line, int level, String text) {
    }

    private static List<Heading> headings(String[] lines) {
        List<Heading> list = new ArrayList<>();
        boolean inFence = false;
        String fenceMarker = null;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].stripLeading();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                String marker = t.substring(0, 3);
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker;
                } else if (marker.equals(fenceMarker)) {
                    inFence = false;
                }
                continue;
            }
            if (inFence || !lines[i].startsWith("#")) continue;
            int level = 0;
            while (level < lines[i].length() && lines[i].charAt(level) == '#') level++;
            if (level > 6 || level >= lines[i].length() || lines[i].charAt(level) != ' ') continue;
            String text = cleanHeading(lines[i].substring(level + 1));
            if (!text.isEmpty()) list.add(new Heading(i, level, text));
        }
        return list;
    }

    private static Heading headingAt(List<Heading> headings, int line) {
        for (Heading h : headings) {
            if (h.line == line) return h;
        }
        return null;
    }

    private static String cleanHeading(String raw) {
        String t = raw.trim().replaceAll("\\s+#+\\s*$", "");
        t = t.replace("**", "").replace("__", "").replace("`", "");
        return t.trim();
    }

    private static String removeLine(String[] lines, int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i != index) sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}

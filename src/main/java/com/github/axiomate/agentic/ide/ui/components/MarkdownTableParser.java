package com.github.axiomate.agentic.ide.ui.components;

import javax.swing.SwingConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for GitHub Flavored Markdown (GFM) tables within AI agent text responses.
 * Extracts plain text segments and tabular data segments with column alignment rules.
 */
public final class MarkdownTableParser {

    private MarkdownTableParser() {
    }

    public sealed interface Segment permits TextSegment, TableSegment, CodeBlockSegment {
    }

    /** Plain text segment before, between, or after tables or code blocks. */
    public record TextSegment(String text) implements Segment {
    }

    /**
     * Parsed fenced code block representation (```lang ... ```).
     *
     * @param language    programming language or tag (e.g. java, python, xml)
     * @param code        the raw code contents inside the block
     * @param rawMarkdown the full markdown snippet including delimiters
     */
    public record CodeBlockSegment(
            String language,
            String code,
            String rawMarkdown
    ) implements Segment {
    }

    /**
     * Parsed Markdown table representation.
     *
     * @param headers     list of column header titles
     * @param alignments  list of SwingConstants alignment values (LEFT, CENTER, RIGHT)
     * @param rows        list of rows, where each row is a list of cell contents
     * @param rawMarkdown original markdown source lines for this table
     */
    public record TableSegment(
            List<String> headers,
            List<Integer> alignments,
            List<List<String>> rows,
            String rawMarkdown
    ) implements Segment {

        public String toTsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.join("\t", headers)).append('\n');
            for (List<String> row : rows) {
                sb.append(String.join("\t", row)).append('\n');
            }
            return sb.toString();
        }

        public String toMarkdown() {
            if (rawMarkdown != null && !rawMarkdown.isBlank()) {
                return rawMarkdown;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|");
            for (int align : alignments) {
                if (align == SwingConstants.CENTER) sb.append(" :---: |");
                else if (align == SwingConstants.RIGHT) sb.append(" ---: |");
                else sb.append(" --- |");
            }
            sb.append('\n');
            for (List<String> row : rows) {
                sb.append("| ").append(String.join(" | ", row)).append(" |\n");
            }
            return sb.toString();
        }
    }

    /**
     * Fast check whether input string could contain a Markdown table.
     */
    public static boolean containsTable(String text) {
        return text != null && text.contains("|") && text.contains("-");
    }

    /**
     * Checks whether the input string contains any Markdown structures
     * (tables, code blocks, headings, bold, inline code, or bullet lists).
     */
    public static boolean containsMarkdown(String text) {
        if (text == null || text.isBlank()) return false;
        return containsTable(text)
                || text.contains("```")
                || text.contains("~~~")
                || text.contains("##")
                || text.contains("# ")
                || text.contains("**")
                || text.contains("`")
                || text.contains("~~")
                || text.matches("(?s).*^\\s*[-*]\\s+.*");
    }

    /**
     * Splits a single line into cell values by pipe '|', properly ignoring escaped pipes '\|'.
     */
    public static List<String> splitRow(String line) {
        if (line == null) return List.of();
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                cur.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /**
     * Validates whether a line matches the GFM table delimiter specification (e.g. `|:---|:---:|---:|`).
     */
    public static boolean isDelimiterRow(String line) {
        if (line == null || !line.contains("-")) return false;
        List<String> cells = splitRow(line);
        if (cells.isEmpty()) return false;
        for (String cell : cells) {
            String clean = cell.replaceAll("\\s+", "");
            if (clean.isEmpty() || !clean.matches("^:?-+:?$")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines column alignment (SwingConstants.LEFT, CENTER, RIGHT) from delimiter cell syntax.
     */
    public static int parseAlignment(String cell) {
        String clean = cell.replaceAll("\\s+", "");
        boolean left = clean.startsWith(":");
        boolean right = clean.endsWith(":");
        if (left && right) {
            return SwingConstants.CENTER;
        } else if (right) {
            return SwingConstants.RIGHT;
        } else {
            return SwingConstants.LEFT;
        }
    }

    private static boolean couldBeHeaderOrRow(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return !trimmed.isEmpty() && trimmed.contains("|");
    }

    /**
     * Parses an arbitrary assistant response into ordered text, code, and table segments.
     */
    public static List<Segment> parseSegments(String input) {
        List<Segment> segments = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return segments;
        }
        if (!containsMarkdown(input)) {
            segments.add(new TextSegment(input));
            return segments;
        }

        String[] lines = input.split("\\R", -1);
        StringBuilder currentText = new StringBuilder();

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // Fenced code block: ```lang ... ``` or ~~~lang ... ~~~
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String fence = trimmed.startsWith("```") ? "```" : "~~~";
                String lang = trimmed.substring(fence.length()).trim();

                if (currentText.length() > 0) {
                    segments.add(new TextSegment(currentText.toString()));
                    currentText.setLength(0);
                }

                StringBuilder codeBuilder = new StringBuilder();
                StringBuilder rawBuilder = new StringBuilder();
                rawBuilder.append(line).append('\n');

                int c = i + 1;
                while (c < lines.length) {
                    String codeLine = lines[c];
                    String codeTrimmed = codeLine.trim();
                    if (codeTrimmed.startsWith(fence)) {
                        rawBuilder.append(codeLine).append('\n');
                        c++;
                        break;
                    }
                    codeBuilder.append(codeLine);
                    if (c < lines.length - 1) codeBuilder.append('\n');
                    rawBuilder.append(codeLine).append('\n');
                    c++;
                }

                segments.add(new CodeBlockSegment(lang, codeBuilder.toString(), rawBuilder.toString().stripTrailing()));
                i = c;
                continue;
            }

            // Check if current line + next line forms a valid markdown table
            if (i + 1 < lines.length && couldBeHeaderOrRow(line) && isDelimiterRow(lines[i + 1])) {
                List<String> headers = splitRow(line);
                List<String> delimiterCells = splitRow(lines[i + 1]);

                if (!headers.isEmpty() && Math.abs(delimiterCells.size() - headers.size()) <= 1) {
                    int colCount = Math.max(headers.size(), delimiterCells.size());
                    while (headers.size() < colCount) {
                        headers.add("");
                    }

                    List<Integer> alignments = new ArrayList<>();
                    for (int c = 0; c < colCount; c++) {
                        if (c < delimiterCells.size()) {
                            alignments.add(parseAlignment(delimiterCells.get(c)));
                        } else {
                            alignments.add(SwingConstants.LEFT);
                        }
                    }

                    // Flush any pending text
                    if (currentText.length() > 0) {
                        segments.add(new TextSegment(currentText.toString()));
                        currentText.setLength(0);
                    }

                    StringBuilder rawTable = new StringBuilder();
                    rawTable.append(line).append('\n').append(lines[i + 1]).append('\n');

                    List<List<String>> rows = new ArrayList<>();
                    int r = i + 2;
                    while (r < lines.length) {
                        String rowLine = lines[r];
                        String rowTrimmed = rowLine.trim();
                        if (rowTrimmed.isEmpty() || rowTrimmed.startsWith("```") || rowTrimmed.startsWith("~~~")
                                || rowTrimmed.startsWith("#") || !couldBeHeaderOrRow(rowLine)) {
                            break;
                        }

                        List<String> rawCells = splitRow(rowLine);
                        List<String> normalizedRow = new ArrayList<>(rawCells);
                        while (normalizedRow.size() < colCount) {
                            normalizedRow.add("");
                        }
                        if (normalizedRow.size() > colCount) {
                            normalizedRow = new ArrayList<>(normalizedRow.subList(0, colCount));
                        }
                        rows.add(normalizedRow);
                        rawTable.append(rowLine).append('\n');
                        r++;
                    }

                    segments.add(new TableSegment(headers, alignments, rows, rawTable.toString().stripTrailing()));
                    i = r;
                    continue;
                }
            }

            currentText.append(line);
            if (i < lines.length - 1) currentText.append('\n');
            i++;
        }

        if (currentText.length() > 0) {
            segments.add(new TextSegment(currentText.toString()));
        }

        return segments;
    }
}

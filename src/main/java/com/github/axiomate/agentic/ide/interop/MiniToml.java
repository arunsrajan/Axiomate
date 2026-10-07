package com.github.axiomate.agentic.ide.interop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal TOML reader covering the subset used by agent configuration files such as
 * {@code ~/.codex/config.toml}: tables, dotted keys, basic/literal (multi-line) strings, arrays,
 * inline tables, booleans and numbers. Arrays of tables are parsed into lists of maps.
 */
public final class MiniToml {

    private final String src;
    private int pos;

    private MiniToml(String src) {
        this.src = src;
    }

    public static Map<String, Object> parse(String toml) {
        return new MiniToml(toml == null ? "" : toml.replace("\r\n", "\n")).parseDocument();
    }

    /**
     * Quotes a TOML key when it is not a valid bare key.
     */
    public static String key(String k) {
        return k.matches("[A-Za-z0-9_-]+") ? k : string(k);
    }

    public static String string(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDocument() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipWhitespaceAndComments(true);
            if (pos >= src.length()) break;
            char c = src.charAt(pos);
            if (c == '[') {
                boolean arrayTable = pos + 1 < src.length() && src.charAt(pos + 1) == '[';
                pos += arrayTable ? 2 : 1;
                List<String> path = parseKeyPath(']');
                expect(']');
                if (arrayTable) expect(']');
                if (arrayTable) {
                    Map<String, Object> parent = table(root, path.subList(0, path.size() - 1));
                    Object existing = parent.get(path.get(path.size() - 1));
                    List<Object> list = existing instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
                    parent.put(path.get(path.size() - 1), list);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    list.add(entry);
                    current = entry;
                } else {
                    current = table(root, path);
                }
                skipToLineEnd();
            } else {
                List<String> path = parseKeyPath('=');
                skipInlineWhitespace();
                expect('=');
                skipInlineWhitespace();
                Object value = parseValue();
                Map<String, Object> target = table(current, path.subList(0, path.size() - 1));
                target.put(path.get(path.size() - 1), value);
                skipToLineEnd();
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> base, List<String> path) {
        Map<String, Object> t = base;
        for (String k : path) {
            Object next = t.get(k);
            if (next instanceof Map<?, ?> m) {
                t = (Map<String, Object>) m;
            } else if (next instanceof List<?> l && !l.isEmpty() && l.get(l.size() - 1) instanceof Map<?, ?> m) {
                t = (Map<String, Object>) m;
            } else {
                Map<String, Object> created = new LinkedHashMap<>();
                t.put(k, created);
                t = created;
            }
        }
        return t;
    }

    private List<String> parseKeyPath(char terminator) {
        List<String> parts = new ArrayList<>();
        while (pos < src.length()) {
            skipInlineWhitespace();
            char c = src.charAt(pos);
            if (c == '"') {
                parts.add(parseBasicString());
            } else if (c == '\'') {
                parts.add(parseLiteralString());
            } else {
                int start = pos;
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_' || src.charAt(pos) == '-')) {
                    pos++;
                }
                if (start == pos) throw error("Invalid key");
                parts.add(src.substring(start, pos));
            }
            skipInlineWhitespace();
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                continue;
            }
            if (pos < src.length() && src.charAt(pos) == terminator) break;
            throw error("Expected '" + terminator + "'");
        }
        return parts;
    }

    private Object parseValue() {
        if (pos >= src.length()) throw error("Missing value");
        char c = src.charAt(pos);
        if (src.startsWith("\"\"\"", pos)) return parseMultiline("\"\"\"", true);
        if (src.startsWith("'''", pos)) return parseMultiline("'''", false);
        if (c == '"') return parseBasicString();
        if (c == '\'') return parseLiteralString();
        if (c == '[') return parseArray();
        if (c == '{') return parseInlineTable();
        int start = pos;
        while (pos < src.length() && ",]}#\n".indexOf(src.charAt(pos)) < 0) pos++;
        String token = src.substring(start, pos).trim();
        if (token.equals("true")) return Boolean.TRUE;
        if (token.equals("false")) return Boolean.FALSE;
        String num = token.replace("_", "");
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException ignored) {
        }
        return token; // dates and anything else stay as raw text
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments(true);
            if (pos >= src.length()) throw error("Unterminated array");
            if (src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            list.add(parseValue());
            skipWhitespaceAndComments(true);
            if (pos < src.length() && src.charAt(pos) == ',') pos++;
        }
    }

    private Map<String, Object> parseInlineTable() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            skipInlineWhitespace();
            if (pos >= src.length()) throw error("Unterminated inline table");
            if (src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            List<String> path = parseKeyPath('=');
            expect('=');
            skipInlineWhitespace();
            table(map, path.subList(0, path.size() - 1)).put(path.get(path.size() - 1), parseValue());
            skipInlineWhitespace();
            if (pos < src.length() && src.charAt(pos) == ',') pos++;
        }
    }

    private String parseBasicString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\' && pos < src.length()) {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private String parseLiteralString() {
        expect('\'');
        int end = src.indexOf('\'', pos);
        if (end < 0) throw error("Unterminated literal string");
        String s = src.substring(pos, end);
        pos = end + 1;
        return s;
    }

    private String parseMultiline(String delimiter, boolean basic) {
        pos += 3;
        if (pos < src.length() && src.charAt(pos) == '\n') pos++;
        int end = src.indexOf(delimiter, pos);
        if (end < 0) throw error("Unterminated multi-line string");
        String s = src.substring(pos, end);
        pos = end + 3;
        return basic ? s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\") : s;
    }

    private void skipWhitespaceAndComments(boolean newlines) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (c == ' ' || c == '\t' || (newlines && c == '\n')) {
                pos++;
            } else {
                break;
            }
        }
    }

    private void skipInlineWhitespace() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
    }

    private void skipToLineEnd() {
        skipWhitespaceAndComments(false);
        if (pos < src.length() && src.charAt(pos) == '\n') pos++;
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) throw error("Expected '" + c + "'");
        pos++;
    }

    private IllegalArgumentException error(String msg) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, src.length()); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return new IllegalArgumentException("TOML parse error at line " + line + ": " + msg);
    }
}

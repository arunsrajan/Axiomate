package com.github.axiomate.agentic.ide.interop;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and resolves {@code @path} imports in Claude Code (CLAUDE.md) and Gemini CLI (GEMINI.md)
 * memory files.
 * <p>
 * Both agents expand {@code @path/to/file} references in place: relative paths resolve against the
 * importing file's directory and {@code @~/...} against the home directory. References inside inline
 * code spans and fenced code blocks are ignored, and nesting stops after {@value #MAX_DEPTH} hops.
 * Only Markdown/text files are followed, since only those make meaningful memories.
 */
public final class MemoryImports {

    public static final int MAX_DEPTH = 5;
    public static final List<String> IMPORTABLE_SUFFIXES = List.of(".md", ".mdc", ".markdown", ".txt");

    /** An {@code @} at the start of a line or after whitespace / an opening bracket, followed by a path. */
    private static final Pattern REF = Pattern.compile("(?<![^\\s(\\[])@([^\\s`'\"<>()\\[\\]{}]+)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final Pattern IMPORT_ONLY_LINE = Pattern.compile("^(?:[-*+]\\s+)?@\\S+(?:\\s+@\\S+)*$");
    private static final String TRAILING_PUNCTUATION = ".,;:!?";

    private MemoryImports() {
    }

    /**
     * The raw {@code @} references in a document, in order, without duplicates. Tokens that do not
     * look like file paths ({@code @Override}, {@code @username}) are skipped.
     */
    public static List<String> findReferences(String text) {
        List<String> refs = new ArrayList<>();
        if (text == null || text.isEmpty()) return refs;
        boolean inFence = false;
        String fenceMarker = null;
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String t = line.stripLeading();
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
            if (inFence || line.indexOf('@') < 0) continue;
            Matcher m = REF.matcher(INLINE_CODE.matcher(line).replaceAll(" "));
            while (m.find()) {
                String ref = trimTrailingPunctuation(m.group(1));
                if (looksLikePath(ref) && !refs.contains(ref)) refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Removes lines that consist only of import references (optionally as a list item), since the
     * imported files become memories of their own. Lines inside fenced code blocks are kept.
     */
    public static String stripImportLines(String text) {
        if (text == null || text.indexOf('@') < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        boolean inFence = false;
        String fenceMarker = null;
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            boolean fenceLine = t.startsWith("```") || t.startsWith("~~~");
            if (fenceLine) {
                String marker = t.substring(0, 3);
                if (!inFence) {
                    inFence = true;
                    fenceMarker = marker;
                } else if (marker.equals(fenceMarker)) {
                    inFence = false;
                }
            } else if (!inFence && isImportOnlyLine(t)) {
                continue;
            }
            sb.append(line);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean isImportOnlyLine(String trimmed) {
        if (trimmed.isEmpty() || !IMPORT_ONLY_LINE.matcher(trimmed).matches()) return false;
        Matcher m = REF.matcher(trimmed);
        boolean any = false;
        while (m.find()) {
            if (!looksLikePath(trimTrailingPunctuation(m.group(1)))) return false;
            any = true;
        }
        return any;
    }

    /**
     * Resolves a reference against the importing file's directory (or the home directory for
     * {@code ~/}); returns null when the reference is not a valid path.
     */
    public static Path resolve(String ref, Path importingFileDir, Path homeDir) {
        try {
            Path p;
            if (ref.startsWith("~/") || ref.startsWith("~\\")) {
                if (homeDir == null) return null;
                p = homeDir.resolve(ref.substring(2));
            } else {
                Path raw = Paths.get(ref);
                p = raw.isAbsolute() || importingFileDir == null ? raw : importingFileDir.resolve(raw);
            }
            return p.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public static boolean isImportable(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
        for (String s : IMPORTABLE_SUFFIXES) {
            if (name.endsWith(s)) return true;
        }
        return false;
    }

    /** A path has a separator or a file extension; this keeps {@code @Override} and {@code @team} out. */
    static boolean looksLikePath(String ref) {
        if (ref.isEmpty() || ref.equals("~") || ref.startsWith("@")) return false;
        if (ref.indexOf('/') >= 0 || ref.indexOf('\\') >= 0) return true;
        int dot = ref.lastIndexOf('.');
        return dot > 0 && dot < ref.length() - 1 && ref.substring(dot + 1).matches("[A-Za-z0-9]+");
    }

    private static String trimTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }
}

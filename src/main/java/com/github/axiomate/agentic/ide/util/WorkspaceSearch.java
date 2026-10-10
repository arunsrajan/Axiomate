package com.github.axiomate.agentic.ide.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text search across a project folder, shared by Find in Files, Quick Open and the agent's {@code search_files} tool.
 * Build output, dependency and VCS folders, binary files and very large files are skipped.
 */
public final class WorkspaceSearch {

    /** Folders that hold build output, dependencies or VCS data rather than the project's own sources. */
    public static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "target", "build", "out", "dist", "node_modules", "bower_components",
            ".idea", ".vscode", ".settings", ".gradle", ".mvn", "bin", "obj", "__pycache__", ".venv", "venv",
            ".tox", ".pytest_cache", ".next", ".nuxt", ".cache", ".axiomate");

    /** Files larger than this are not searched (minified bundles, logs, data dumps). */
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** Matched lines longer than this are cut around the match for display. */
    static final int MAX_LINE_CHARS = 300;

    /**
     * @param include comma-separated globs ({@code *.java, src/**}); a glob without '/' matches file names,
     *                one with '/' the path relative to the search root; blank means every file
     */
    public record Options(String query, boolean regex, boolean matchCase, boolean wholeWord, String include, int maxResults) {

        public static Options literal(String query) {
            return new Options(query, false, false, false, "", 500);
        }
    }

    /**
     * @param line   1-based line number
     * @param column 0-based offset of the match in {@code lineText}
     */
    public record Match(Path file, String relativePath, int line, int column, int length, String lineText) {
    }

    /**
     * @param truncated true when {@code maxResults} was reached before every file was searched
     */
    public record Result(List<Match> matches, int filesSearched, int filesMatched, boolean truncated, boolean cancelled) {
    }

    private WorkspaceSearch() {
    }

    /** The pattern {@code options} describe. Throws {@link java.util.regex.PatternSyntaxException} for a bad regex. */
    public static Pattern compile(Options options) {
        String q = options.query() == null ? "" : options.query();
        String body = options.regex() ? q : Pattern.quote(q);
        if (options.wholeWord()) body = "(?<![\\w$])(?:" + body + ")(?![\\w$])";
        int flags = Pattern.MULTILINE | (options.matchCase() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return Pattern.compile(body, flags);
    }

    public static Result search(Path root, Options options, BooleanSupplier cancelled) throws IOException {
        Pattern pattern = compile(options);
        List<Pattern> include = globs(options.include());
        int max = options.maxResults() > 0 ? options.maxResults() : Integer.MAX_VALUE;
        List<Match> matches = new ArrayList<>();
        int[] counts = new int[2]; // files searched, files matched
        boolean[] stop = new boolean[2]; // truncated, cancelled
        walk(root, root, include, cancelled, stop, file -> {
            byte[] bytes = readText(file);
            if (bytes == null) return;
            counts[0]++;
            String text = new String(bytes, StandardCharsets.UTF_8);
            String rel = relative(root, file);
            int before = matches.size();
            matchLines(file, rel, text, pattern, matches, max);
            if (matches.size() > before) counts[1]++;
            if (matches.size() >= max) stop[0] = true;
        });
        return new Result(List.copyOf(matches), counts[0], counts[1], stop[0], stop[1]);
    }

    /** Every file under {@code root} that a search would look at, as '/'-separated relative paths, sorted. */
    public static List<String> listFiles(Path root, int limit) throws IOException {
        List<String> out = new ArrayList<>();
        boolean[] stop = new boolean[2];
        walk(root, root, List.of(), () -> false, stop, file -> {
            out.add(relative(root, file));
            if (out.size() >= limit) stop[0] = true;
        });
        return out;
    }

    /**
     * Files ranked for Quick Open: a match in the file name beats one spread across folders. An empty query keeps
     * the given order.
     */
    public static List<String> rankFiles(List<String> files, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (q.isEmpty()) return files.subList(0, Math.min(limit, files.size()));
        record Scored(String path, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String f : files) {
            String lower = f.toLowerCase(Locale.ROOT);
            String name = lower.substring(lower.lastIndexOf('/') + 1);
            int byName = q.contains("/") ? -1 : fuzzyScore(q, name);
            int byPath = fuzzyScore(q, lower);
            int s = Math.max(byName >= 0 ? byName + 500 : -1, byPath);
            if (s >= 0) scored.add(new Scored(f, s - f.length() / 8));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(Scored::path));
        return scored.stream().limit(limit).map(Scored::path).toList();
    }

    /** Subsequence match score: -1 when the query's characters do not all appear in order; higher is better. */
    public static int fuzzyScore(String query, String text) {
        if (query.isEmpty()) return 0;
        int idx = text.indexOf(query);
        if (idx >= 0) return 1000 - idx * 2;
        int score = 0;
        int ti = 0;
        int streak = 0;
        for (char qc : query.toCharArray()) {
            int found = text.indexOf(qc, ti);
            if (found < 0) return -1;
            streak = found == ti ? streak + 1 : 0;
            score += 10 + streak * 5 - Math.min(9, found - ti);
            if (found == 0 || "/._-".indexOf(text.charAt(found - 1)) >= 0) score += 8;
            ti = found + 1;
        }
        return score;
    }

    /** True when {@code relativePath} matches one of the comma-separated globs (blank matches everything). */
    public static boolean includes(String include, String relativePath) {
        List<Pattern> globs = globs(include);
        return globs.isEmpty() || matchesAny(globs, relativePath);
    }

    // ------------------------------------------------------------------------------------------------------------

    private interface FileVisitor {
        void visit(Path file) throws IOException;
    }

    /** Sorted depth-first walk, so results come in a stable order; symbolic links are not followed. */
    private static void walk(Path root, Path dir, List<Pattern> include, BooleanSupplier cancelled, boolean[] stop,
                             FileVisitor visitor) throws IOException {
        List<Path> children;
        try (Stream<Path> s = Files.list(dir)) {
            children = s.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))).toList();
        } catch (IOException e) {
            return; // unreadable folder: skip it, keep searching the rest
        }
        for (Path child : children) {
            if (stop[0] || stop[1]) return;
            if (cancelled.getAsBoolean()) {
                stop[1] = true;
                return;
            }
            if (Files.isSymbolicLink(child)) continue;
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                if (!IGNORED_DIRS.contains(child.getFileName().toString())) walk(root, child, include, cancelled, stop, visitor);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                    && (include.isEmpty() || matchesAny(include, relative(root, child)))) {
                visitor.visit(child);
            }
        }
    }

    /** The file's bytes, or null when it is too large, unreadable or binary. */
    private static byte[] readText(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            byte[] bytes;
            try (InputStream in = Files.newInputStream(file)) {
                bytes = in.readAllBytes();
            }
            for (int i = 0; i < Math.min(bytes.length, 8_000); i++) {
                if (bytes[i] == 0) return null;
            }
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    private static void matchLines(Path file, String rel, String text, Pattern pattern, List<Match> out, int max) {
        Matcher m = pattern.matcher(text);
        int lineNo = 1;
        int lineStart = 0;
        int scanned = 0; // offset up to which newlines have been counted
        int lastLine = -1;
        while (m.find() && out.size() < max) {
            if (m.end() == m.start() && m.start() >= text.length()) break;
            for (int i = scanned; i < m.start(); i++) {
                if (text.charAt(i) == '\n') {
                    lineNo++;
                    lineStart = i + 1;
                }
            }
            scanned = m.start();
            if (lineNo == lastLine) continue; // one result per line, like grep
            lastLine = lineNo;
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(lineStart, lineEnd);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int col = m.start() - lineStart;
            int len = Math.max(0, Math.min(m.end(), lineStart + line.length()) - m.start());
            if (line.length() > MAX_LINE_CHARS) {
                int from = Math.max(0, Math.min(col - 80, line.length() - MAX_LINE_CHARS));
                line = (from > 0 ? "…" : "") + line.substring(from, Math.min(line.length(), from + MAX_LINE_CHARS)) + "…";
                col = col - from + (from > 0 ? 1 : 0);
                len = Math.min(len, Math.max(0, line.length() - col));
            }
            out.add(new Match(file, rel, lineNo, col, len, line));
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<Pattern> globs(String include) {
        List<Pattern> out = new ArrayList<>();
        if (include == null || include.isBlank()) return out;
        for (String g : splitGlobs(include)) {
            String glob = g.trim().replace('\\', '/');
            if (glob.isEmpty()) continue;
            if (glob.startsWith("./")) glob = glob.substring(2);
            out.add(Pattern.compile((glob.contains("/") ? "" : "(?:.*/)?") + globToRegex(glob),
                    OSUtils.isWindows() ? Pattern.CASE_INSENSITIVE : 0));
        }
        return out;
    }

    /** Splits on ',' and ';' outside braces, so "*.{java,kt}, docs/**" is two globs. */
    private static List<String> splitGlobs(String include) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : include.toCharArray()) {
            if (c == '{') depth++;
            if (c == '}') depth = Math.max(0, depth - 1);
            if ((c == ',' || c == ';') && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private static boolean matchesAny(List<Pattern> globs, String relativePath) {
        for (Pattern p : globs) {
            if (p.matcher(relativePath).matches()) return true;
        }
        return false;
    }

    /** {@code **} spans folders, {@code *} and {@code ?} stay inside one; a trailing folder matches its contents. */
    static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    boolean dirPart = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    sb.append(dirPart ? "(?:.*/)?" : ".*");
                    i += dirPart ? 2 : 1;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if (c == '{') {
                int close = glob.indexOf('}', i);
                if (close < 0) {
                    sb.append("\\{");
                } else {
                    List<String> alts = new ArrayList<>();
                    for (String a : glob.substring(i + 1, close).split(",")) alts.add(Pattern.quote(a));
                    sb.append("(?:").append(String.join("|", alts)).append(')');
                    i = close;
                }
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (glob.endsWith("/")) sb.append(".*");
        return sb.toString();
    }
}

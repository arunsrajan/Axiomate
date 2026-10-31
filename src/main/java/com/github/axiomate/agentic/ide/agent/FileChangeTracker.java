package com.github.axiomate.agentic.ide.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The files an agent run changed, with lines added and removed, for the summary at the end of its answer.
 * <p>
 * The agent's file tools record a file's content before they first change it. In a git repository, files changed
 * any other way (shell commands) are found by comparing {@code git status} before and after the run; their earlier
 * content comes from the start of the run (files that were already modified) or from HEAD.
 * State is per thread: a run's tools execute on the run's own thread.
 */
public final class FileChangeTracker {

    private static final Logger log = LoggerFactory.getLogger(FileChangeTracker.class);

    public enum Kind {MODIFIED, CREATED, DELETED}

    /**
     * @param path    relative to the project folder, with '/' separators
     * @param added   lines added, or -1 when not counted (binary or very large file)
     * @param removed lines removed, or -1 when not counted
     */
    public record FileChange(String path, int added, int removed, Kind kind) {
        public boolean counted() {
            return added >= 0 && removed >= 0;
        }
    }

    /** A file's content at one moment: absent, text, or present but not countable (binary or too large). */
    record Snapshot(boolean exists, String text) {
        static final Snapshot ABSENT = new Snapshot(false, null);

        boolean countable() {
            return exists && text != null;
        }
    }

    static final long MAX_COUNTED_BYTES = 2L * 1024 * 1024;
    private static final int MAX_GIT_BASELINE_FILES = 500;
    private static final int MAX_EDIT_DISTANCE = 4_000;
    private static final Pattern LINE = Pattern.compile(
            "^(.+?)(?: \\+(\\d+) -(\\d+))?(?: \\((new|deleted)\\))?(?: \\((not counted)\\))?$");

    private static final class Run {
        final Path project;
        /** Content before the run's first change, by absolute normalized path; tools fill this in. */
        final Map<Path, Snapshot> before = new LinkedHashMap<>();
        /** git work tree root, or null when the project is not in a repository (or git is missing). */
        Path gitRoot;
        /** Files git reported as changed when the run started, with their content then. */
        final Map<Path, Snapshot> gitDirtyAtStart = new HashMap<>();

        Run(Path project) {
            this.project = project;
        }
    }

    private static final ThreadLocal<Run> RUN = new ThreadLocal<>();

    private FileChangeTracker() {
    }

    /** Starts tracking for the run on this thread. */
    public static void begin(File projectDir) {
        Path project = projectDir != null ? projectDir.toPath().toAbsolutePath().normalize() : null;
        Run run = new Run(project);
        if (project != null) {
            run.gitRoot = gitRoot(project);
            List<Path> dirty = run.gitRoot != null ? gitChangedFiles(run.gitRoot) : List.of();
            if (dirty.size() > MAX_GIT_BASELINE_FILES) {
                // Too many changes already: without their content at the start, any of them could pass for the
                // agent's work. Count only the files the agent's tools change.
                run.gitRoot = null;
            } else {
                for (Path p : dirty) run.gitDirtyAtStart.put(p, read(p.toFile()));
            }
        }
        RUN.set(run);
    }

    /** True while a run is being tracked on this thread. */
    public static boolean isTracking() {
        return RUN.get() != null;
    }

    /**
     * Records a file's content before a tool changes it; only the first record per run counts.
     *
     * @param before the content now, or null when the file does not exist yet
     */
    public static void recordBefore(File file, String before) {
        Run run = RUN.get();
        if (run == null || file == null) return;
        Path p = file.toPath().toAbsolutePath().normalize();
        run.before.putIfAbsent(p, before == null ? Snapshot.ABSENT : new Snapshot(true, before));
    }

    /** Ends tracking and returns what the run changed, ordered by path; empty when nothing changed. */
    public static List<FileChange> finish() {
        Run run = RUN.get();
        RUN.remove();
        if (run == null) return List.of();
        Set<Path> candidates = new LinkedHashSet<>(run.before.keySet());
        if (run.gitRoot != null) candidates.addAll(gitChangedFiles(run.gitRoot));
        List<FileChange> changes = new ArrayList<>();
        for (Path p : candidates) {
            Snapshot before = run.before.containsKey(p) ? run.before.get(p)
                    : run.gitDirtyAtStart.containsKey(p) ? run.gitDirtyAtStart.get(p)
                    : headContent(run.gitRoot, p);
            Snapshot after = read(p.toFile());
            FileChange c = change(displayPath(run, p), before, after);
            if (c != null) changes.add(c);
        }
        changes.sort(java.util.Comparator.comparing(FileChange::path));
        return changes;
    }

    /** Forgets this thread's run without reporting it (e.g. after the summary was already taken). */
    public static void clear() {
        RUN.remove();
    }

    /** " (+4 -1)" for a tool's result, or "" when nothing changed or the text cannot be counted. */
    public static String deltaLabel(String before, String after) {
        if (after == null || (before != null && before.equals(after))) return "";
        int[] d = lineDelta(before, after);
        return " (+" + d[0] + " -" + d[1] + ")";
    }

    /** Lines added and removed going from {@code before} to {@code after} (null means no file): {added, removed}. */
    public static int[] lineDelta(String before, String after) {
        Map<String, Integer> lineIds = new HashMap<>();
        int[] a = ids(lines(before), lineIds);
        int[] b = ids(lines(after), lineIds);
        int start = 0;
        while (start < a.length && start < b.length && a[start] == b[start]) start++;
        int endA = a.length;
        int endB = b.length;
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--;
            endB--;
        }
        int n = endA - start;
        int m = endB - start;
        if (n == 0 || m == 0) return new int[]{m, n};
        int d = editDistance(a, start, endA, b, start, endB);
        if (d < 0) return approximateDelta(a, start, endA, b, start, endB);
        int common = (n + m - d) / 2;
        return new int[]{m - common, n - common};
    }

    /** The summary as stored with the session and shown in the chat: a header line, then one line per file. */
    public static String describe(List<FileChange> changes) {
        int added = 0;
        int removed = 0;
        for (FileChange c : changes) {
            if (c.counted()) {
                added += c.added();
                removed += c.removed();
            }
        }
        StringBuilder sb = new StringBuilder("Changed ").append(changes.size()).append(changes.size() == 1 ? " file" : " files")
                .append(" (+").append(added).append(" -").append(removed).append(')');
        for (FileChange c : changes) {
            sb.append('\n').append(c.path());
            if (c.counted()) sb.append(" +").append(c.added()).append(" -").append(c.removed());
            if (c.kind() == Kind.CREATED) sb.append(" (new)");
            if (c.kind() == Kind.DELETED) sb.append(" (deleted)");
            if (!c.counted()) sb.append(" (not counted)");
        }
        return sb.toString();
    }

    /** Reads back {@link #describe}'s text. */
    public static List<FileChange> parse(String text) {
        List<FileChange> out = new ArrayList<>();
        if (text == null) return out;
        String[] lines = text.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            Matcher m = LINE.matcher(lines[i]);
            if (!m.matches() || lines[i].isBlank()) continue;
            Kind kind = "new".equals(m.group(4)) ? Kind.CREATED : "deleted".equals(m.group(4)) ? Kind.DELETED : Kind.MODIFIED;
            int added = m.group(2) != null ? Integer.parseInt(m.group(2)) : -1;
            int removed = m.group(3) != null ? Integer.parseInt(m.group(3)) : -1;
            out.add(new FileChange(m.group(1), added, removed, kind));
        }
        return out;
    }

    /** The file's text (UTF-8, leniently), or null when it does not exist; binary and huge files are not counted. */
    public static String readText(File file) {
        Snapshot s = read(file);
        return s.text();
    }

    // ------------------------------------------------------------------------------------------------------------

    private static FileChange change(String path, Snapshot before, Snapshot after) {
        if (!before.exists() && !after.exists()) return null;
        if (before.exists() && after.exists() && before.countable() && after.countable() && before.text().equals(after.text())) {
            return null;
        }
        Kind kind = !before.exists() ? Kind.CREATED : !after.exists() ? Kind.DELETED : Kind.MODIFIED;
        boolean countable = (!before.exists() || before.countable()) && (!after.exists() || after.countable());
        if (!countable) {
            return new FileChange(path, -1, -1, kind);
        }
        int[] d = lineDelta(before.exists() ? before.text() : null, after.exists() ? after.text() : null);
        if (kind == Kind.MODIFIED && d[0] == 0 && d[1] == 0) return null; // only line endings differ
        return new FileChange(path, d[0], d[1], kind);
    }

    static Snapshot read(File file) {
        try {
            if (file == null || !file.isFile()) return Snapshot.ABSENT;
            if (Files.size(file.toPath()) > MAX_COUNTED_BYTES) return new Snapshot(true, null);
            return text(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            return Snapshot.ABSENT;
        }
    }

    private static Snapshot text(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++) {
            if (bytes[i] == 0) return new Snapshot(true, null);
        }
        return new Snapshot(true, new String(bytes, StandardCharsets.UTF_8));
    }

    private static String displayPath(Run run, Path p) {
        Path base = run.project != null && p.startsWith(run.project) ? run.project : run.gitRoot;
        Path rel = base != null && p.startsWith(base) ? base.relativize(p) : p;
        return rel.toString().replace('\\', '/');
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        List<String> out = new ArrayList<>();
        for (String line : t.split("\n", -1)) out.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        return out;
    }

    /** Lines as numbers (equal lines, equal numbers, through the shared {@code ids}) so the diff compares ints. */
    private static int[] ids(List<String> lines, Map<String, Integer> ids) {
        int[] out = new int[lines.size()];
        for (int i = 0; i < out.length; i++) {
            Integer id = ids.get(lines.get(i));
            if (id == null) {
                id = ids.size();
                ids.put(lines.get(i), id);
            }
            out[i] = id;
        }
        return out;
    }

    /** Myers' shortest edit script length (insertions + deletions), or -1 beyond {@link #MAX_EDIT_DISTANCE}. */
    private static int editDistance(int[] a, int a0, int a1, int[] b, int b0, int b1) {
        int n = a1 - a0;
        int m = b1 - b0;
        int max = Math.min(n + m, MAX_EDIT_DISTANCE);
        int off = max + 1;
        int[] v = new int[2 * max + 3];
        for (int d = 0; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) ? v[off + k + 1] : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[a0 + x] == b[b0 + y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) return d;
            }
        }
        return -1;
    }

    /** For changes too large to diff exactly: lines that occur more often in one version than the other. */
    private static int[] approximateDelta(int[] a, int a0, int a1, int[] b, int b0, int b1) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = a0; i < a1; i++) counts.merge(a[i], 1, Integer::sum);
        int added = 0;
        for (int i = b0; i < b1; i++) {
            Integer c = counts.get(b[i]);
            if (c != null && c > 0) counts.put(b[i], c - 1);
            else added++;
        }
        int removed = 0;
        for (int c : counts.values()) removed += c;
        return new int[]{added, removed};
    }

    // ------------------------------------------------------------------------------------------------------------ git

    private static Path gitRoot(Path project) {
        byte[] out = git(project, "rev-parse", "--show-toplevel");
        if (out == null) return null;
        String root = new String(out, StandardCharsets.UTF_8).strip();
        return root.isEmpty() ? null : Path.of(root).toAbsolutePath().normalize();
    }

    /** Modified, added, deleted and untracked files (not ignored ones), as absolute paths. */
    private static List<Path> gitChangedFiles(Path root) {
        byte[] out = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        List<Path> files = new ArrayList<>();
        if (out == null) return files;
        String[] entries = new String(out, StandardCharsets.UTF_8).split("\0");
        for (int i = 0; i < entries.length; i++) {
            String e = entries[i];
            if (e.length() < 4) continue;
            String status = e.substring(0, 2);
            files.add(root.resolve(e.substring(3)).toAbsolutePath().normalize());
            if (status.indexOf('R') >= 0 || status.indexOf('C') >= 0) i++; // the entry after a rename is its old path
        }
        return files;
    }

    /** The file as committed in HEAD, or absent when it is not in HEAD (a new file). */
    private static Snapshot headContent(Path root, Path file) {
        if (root == null || !file.startsWith(root)) return Snapshot.ABSENT;
        String rel = root.relativize(file).toString().replace('\\', '/');
        byte[] out = git(root, "show", "HEAD:" + rel);
        if (out == null) return Snapshot.ABSENT;
        if (out.length > MAX_COUNTED_BYTES) return new Snapshot(true, null);
        return text(out);
    }

    /** Runs git in {@code dir}; stdout, or null when git is missing, fails or takes too long. */
    private static byte[] git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    in.transferTo(buf);
                } catch (Exception ignored) {
                    // the process ended or was killed
                }
            }, "git-reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            reader.join(5_000);
            return p.exitValue() == 0 ? buf.toByteArray() : null;
        } catch (Exception e) {
            log.debug("git {} failed: {}", args.length > 0 ? args[0] : "", e.getMessage());
            return null;
        }
    }
}

package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.agent.memory.AgentMemoryStore;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.interop.CodingAgentCatalog.ExportStyle;
import com.github.axiomate.agentic.ide.interop.CodingAgentCatalog.ExportTarget;
import com.github.axiomate.agentic.ide.interop.CodingAgentCatalog.MemoryLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Imports memories, rules and instructions from other coding agents (Claude Code, Codex, Cursor,
 * Antigravity, Gemini CLI, Windsurf, Copilot, Cline, Roo Code, Kiro) into Axiomate's agentic memory,
 * and exports Axiomate memories back into each agent's native instruction files.
 * <p>
 * Imports are idempotent: every memory records its source file, so re-importing a file replaces the
 * memories previously imported from it instead of duplicating them. Exports to shared files
 * (CLAUDE.md, AGENTS.md, GEMINI.md...) only touch a marker-delimited block, preserving user content.
 */
public class AgentMemoryInterop {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryInterop.class);
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_DIR_DEPTH = 4;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * A memory file discovered on disk for an agent.
     */
    public record DetectedSource(MemoryLocation location, Path file, long sizeBytes) {
        public CodingAgent agent() {
            return location.agent();
        }

        public String sourceKey() {
            return location.agent().getId() + "|" + ProjectStateManager.normalizePath(file.toFile());
        }

        public String displayPath(Path projectDir, Path homeDir) {
            if (location.scope() == MemoryScope.PROJECT && projectDir != null && file.startsWith(projectDir)) {
                return projectDir.relativize(file).toString().replace('\\', '/');
            }
            if (homeDir != null && file.startsWith(homeDir)) {
                return "~/" + homeDir.relativize(file).toString().replace('\\', '/');
            }
            return file.toString();
        }
    }

    /**
     * @param replaceExisting       remove memories previously imported from the same file first
     * @param includeManagedContent also import blocks/files that Axiomate itself exported
     * @param scopeToProject        tag project-level sources with the project path so they only apply there
     */
    public record ImportOptions(boolean replaceExisting, boolean includeManagedContent, boolean scopeToProject) {
        public static ImportOptions defaults() {
            return new ImportOptions(true, false, true);
        }
    }

    public record ImportResult(int imported, int replaced, int files, Map<CodingAgent, Integer> perAgent,
                               List<String> warnings) {
    }

    /**
     * A computed, not-yet-written export.
     */
    public record ExportPlan(CodingAgent agent, MemoryScope scope, Path target, ExportStyle style,
                             boolean targetExists, String newContent, int itemCount) {
    }

    private final Path homeDir;

    public AgentMemoryInterop() {
        this(Paths.get(System.getProperty("user.home", ".")));
    }

    public AgentMemoryInterop(Path homeDir) {
        this.homeDir = homeDir;
    }

    public Path getHomeDir() {
        return homeDir;
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * Finds memory files for the given agents. Files shared between agents (e.g. ~/.gemini/GEMINI.md)
     * are reported once, attributed to the first agent that claims them.
     */
    public List<DetectedSource> detect(Path projectDir, Collection<CodingAgent> agents) {
        Map<Path, DetectedSource> found = new LinkedHashMap<>();
        for (CodingAgent agent : agents) {
            for (MemoryLocation loc : CodingAgentCatalog.locations(agent)) {
                Path p = loc.resolve(projectDir, homeDir);
                if (p == null || !Files.exists(p)) continue;
                for (Path f : filesFor(loc, p)) {
                    Path key = f.toAbsolutePath().normalize();
                    found.putIfAbsent(key, new DetectedSource(loc, key, sizeOf(key)));
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    public List<DetectedSource> detectAll(Path projectDir) {
        return detect(projectDir, EnumSet.allOf(CodingAgent.class));
    }

    private List<Path> filesFor(MemoryLocation loc, Path p) {
        if (!loc.directory()) {
            return Files.isRegularFile(p) ? List.of(p) : List.of();
        }
        if (!Files.isDirectory(p)) return List.of();
        List<Path> files;
        try (Stream<Path> s = Files.walk(p, MAX_DIR_DEPTH)) {
            files = s.filter(Files::isRegularFile)
                    .filter(f -> loc.accepts(f.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not scan {}: {}", p, e.getMessage());
            return List.of();
        }
        if (loc.indexFile() != null && files.size() > 1) {
            files.removeIf(f -> f.getFileName().toString().equalsIgnoreCase(loc.indexFile()) && f.getParent().equals(p));
        }
        return files;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Parsing & import
    // ------------------------------------------------------------------

    /**
     * Converts one detected file into memory items without touching any store.
     */
    public List<MemoryItem> parse(DetectedSource src, Path projectDir, ImportOptions opts) throws IOException {
        Path file = src.file();
        String fileName = file.getFileName().toString();
        if (!opts.includeManagedContent() && fileName.toLowerCase().startsWith(CodingAgentCatalog.EXPORT_FILE_BASENAME)) {
            return List.of(); // written by Axiomate's own export
        }
        if (src.sizeBytes() > MAX_FILE_BYTES) {
            throw new IOException("File too large to import (" + src.sizeBytes() / 1024 + " KB): " + file);
        }

        MarkdownDocument doc = MarkdownDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!opts.includeManagedContent()) {
            doc = doc.withoutManagedBlock();
        }

        MemoryLocation loc = src.location();
        String fallbackTitle = humanize(fileName);
        String fmTitle = firstNonNull(doc.frontmatter("name"), doc.frontmatter("title"));
        String description = doc.frontmatter("description");
        String appliesTo = firstNonNull(doc.frontmatter("globs"), doc.frontmatter("applyTo"), doc.frontmatter("fileMatchPattern"));
        boolean alwaysOn = "true".equalsIgnoreCase(doc.frontmatter("alwaysApply"))
                || "always_on".equalsIgnoreCase(doc.frontmatter("trigger"))
                || "always".equalsIgnoreCase(doc.frontmatter("inclusion"));

        List<MarkdownDocument.Section> sections;
        if (loc.splitSections()) {
            sections = doc.sections(fallbackTitle);
        } else {
            String title = firstNonNull(fmTitle, doc.firstHeading(), description, fallbackTitle);
            String body = doc.getBody().strip();
            sections = body.isEmpty() ? List.of() : List.of(new MarkdownDocument.Section(title, body, 0));
        }

        String projectScope = (opts.scopeToProject() && loc.scope() == MemoryScope.PROJECT && projectDir != null)
                ? ProjectStateManager.normalizePath(projectDir.toFile())
                : null;
        String timestamp = lastModified(file);
        String sourceKey = src.sourceKey();

        List<MemoryItem> items = new ArrayList<>();
        int ordinal = 0;
        for (MarkdownDocument.Section section : sections) {
            if (isGeminiSavedMemories(section.title())) {
                for (String bullet : bullets(section.content())) {
                    items.add(build(src, loc, MemoryType.LONG_TERM, truncate(bullet, 70), bullet, List.of("saved-memory"),
                            0.7, sourceKey, projectScope, timestamp, ordinal++));
                }
                continue;
            }
            StringBuilder content = new StringBuilder();
            if (!loc.splitSections() && description != null && !description.equals(section.title())) {
                content.append(description).append("\n\n");
            }
            if (appliesTo != null) {
                content.append("Applies to: ").append(appliesTo).append("\n\n");
            }
            content.append(section.content());

            List<String> extraTags = new ArrayList<>();
            if (alwaysOn) extraTags.add("always-on");
            if (appliesTo != null) extraTags.add("scoped");
            double importance = alwaysOn ? 0.8 : (loc.type() == MemoryType.PROJECT_RULE ? 0.7 : 0.6);

            items.add(build(src, loc, loc.type(), section.title(), content.toString().strip(), extraTags,
                    importance, sourceKey, projectScope, timestamp, ordinal++));
        }
        return items;
    }

    private MemoryItem build(DetectedSource src, MemoryLocation loc, MemoryType type, String title, String content,
                             List<String> extraTags, double importance, String sourceKey, String projectScope,
                             String timestamp, int ordinal) {
        MemoryItem item = new MemoryItem(type, title, content);
        item.setId("ext-" + hash(sourceKey + "#" + ordinal + ":" + title));
        List<String> tags = new ArrayList<>();
        tags.add(loc.agent().getId());
        tags.add("imported");
        tags.add(src.file().getFileName().toString().toLowerCase());
        tags.addAll(extraTags);
        item.setTags(tags);
        item.setImportance(importance);
        item.setSource(sourceKey);
        item.setProjectPath(projectScope);
        if (timestamp != null) item.setTimestamp(timestamp);
        return item;
    }

    /**
     * Parses the given sources and writes them into the memory store in one batch.
     */
    public ImportResult importInto(AgentMemoryStore store, List<DetectedSource> sources, Path projectDir,
                                   ImportOptions opts) {
        List<MemoryItem> all = new ArrayList<>();
        Map<CodingAgent, Integer> perAgent = new EnumMap<>(CodingAgent.class);
        List<String> warnings = new ArrayList<>();
        int replaced = 0;
        int files = 0;

        for (DetectedSource src : sources) {
            try {
                List<MemoryItem> items = parse(src, projectDir, opts);
                if (opts.replaceExisting()) {
                    replaced += store.removeMemoriesBySource(src.sourceKey());
                }
                if (items.isEmpty()) continue;
                all.addAll(items);
                files++;
                perAgent.merge(src.agent(), items.size(), Integer::sum);
            } catch (IOException e) {
                warnings.add(e.getMessage());
                log.warn("Skipping memory source {}: {}", src.file(), e.getMessage());
            }
        }
        store.addMemories(all);
        log.info("Imported {} memories from {} file(s) across {} agent(s)", all.size(), files, perAgent.size());
        return new ImportResult(all.size(), replaced, files, perAgent, warnings);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Selects the memories that make sense to export for a scope: project exports include global
     * memories plus the project's own; user exports include only global memories.
     */
    public static List<MemoryItem> selectForExport(Collection<MemoryItem> all, Path projectDir, MemoryScope scope,
                                                   Set<MemoryType> types) {
        String project = projectDir != null ? ProjectStateManager.normalizePath(projectDir.toFile()) : null;
        return all.stream()
                .filter(m -> m.getType() != null && types.contains(m.getType()))
                .filter(m -> scope == MemoryScope.USER
                        ? m.getProjectPath() == null
                        : m.isVisibleInScope(project))
                .sorted(Comparator.comparing((MemoryItem m) -> m.getType().ordinal())
                        .thenComparing(m -> -m.getImportance()))
                .collect(Collectors.toList());
    }

    public Optional<ExportPlan> planExport(CodingAgent agent, MemoryScope scope, Path projectDir,
                                           List<MemoryItem> items) throws IOException {
        Optional<ExportTarget> maybeTarget = CodingAgentCatalog.exportTarget(agent, scope);
        if (maybeTarget.isEmpty()) return Optional.empty();
        ExportTarget target = maybeTarget.get();
        Path path = target.resolve(projectDir, homeDir);
        if (path == null) return Optional.empty();
        ExportStyle style = target.style();

        // Cline accepts either a .clinerules file or folder; respect whichever the project already uses.
        if (agent == CodingAgent.CLINE && scope == MemoryScope.PROJECT) {
            Path legacy = projectDir.resolve(".clinerules");
            if (Files.isRegularFile(legacy)) {
                path = legacy;
                style = ExportStyle.MANAGED_BLOCK;
            }
        }

        // Never write a file's own imported memories back into it (that would duplicate them).
        String targetSuffix = "|" + ProjectStateManager.normalizePath(path.toFile());
        items = items.stream()
                .filter(m -> m.getSource() == null || !m.getSource().endsWith(targetSuffix))
                .collect(Collectors.toList());

        String body = renderMarkdown(items, style == ExportStyle.DEDICATED_FILE);
        boolean exists = Files.exists(path);
        String content;
        if (style == ExportStyle.MANAGED_BLOCK) {
            String existing = exists ? Files.readString(path, StandardCharsets.UTF_8) : "";
            content = MarkdownDocument.upsertManagedBlock(existing, items.isEmpty() ? "" : body);
        } else {
            StringBuilder sb = new StringBuilder();
            if (target.frontmatter() != null) {
                sb.append("---\n").append(target.frontmatter()).append("\n---\n\n");
            }
            sb.append(body);
            content = sb.toString();
        }
        return Optional.of(new ExportPlan(agent, scope, path, style, exists, content, items.size()));
    }

    public void apply(ExportPlan plan) throws IOException {
        Path parent = plan.target().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(plan.target(), plan.newContent(), StandardCharsets.UTF_8);
        log.info("Exported {} memories to {} ({})", plan.itemCount(), plan.target(), plan.agent().getDisplayName());
    }

    /**
     * Renders memories as agent-readable Markdown grouped by memory type.
     */
    public static String renderMarkdown(List<MemoryItem> items, boolean standaloneFile) {
        StringBuilder sb = new StringBuilder();
        sb.append(standaloneFile ? "# " : "## ").append("Axiomate Agent Memory\n\n");
        sb.append("_Exported from Axiomate IDE on ").append(LocalDate.now())
                .append(standaloneFile
                        ? ". This file is regenerated on every export; edit memories in Axiomate._\n"
                        : ". This block is regenerated on every export; edits outside it are preserved._\n");

        String h2 = standaloneFile ? "## " : "### ";
        String h3 = standaloneFile ? "### " : "#### ";
        Map<MemoryType, List<MemoryItem>> byType = new EnumMap<>(MemoryType.class);
        for (MemoryItem item : items) {
            byType.computeIfAbsent(item.getType(), k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<MemoryType, List<MemoryItem>> e : byType.entrySet()) {
            sb.append('\n').append(h2).append(typeHeading(e.getKey())).append("\n");
            for (MemoryItem item : e.getValue()) {
                sb.append('\n').append(h3).append(item.getTitle() == null ? "Untitled" : item.getTitle().strip()).append("\n\n");
                sb.append(demoteHeadings(item.getContent() == null ? "" : item.getContent().strip(), h3.length() - 1)).append("\n");
            }
        }
        return sb.toString();
    }

    private static String typeHeading(MemoryType type) {
        return switch (type) {
            case PROJECT_RULE -> "Project Rules";
            case LONG_TERM -> "Long-Term Knowledge";
            case EPISODIC -> "Episodic History";
            case WORKING -> "Working Context";
        };
    }

    /** Keeps nested headings inside an item below the item's own heading level. */
    private static String demoteHeadings(String content, int minLevel) {
        StringBuilder sb = new StringBuilder();
        boolean inFence = false;
        for (String line : content.split("\n", -1)) {
            String t = line.stripLeading();
            if (t.startsWith("```") || t.startsWith("~~~")) inFence = !inFence;
            if (!inFence && line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                if (level <= minLevel && level < line.length() && line.charAt(level) == ' ') {
                    line = "#".repeat(Math.min(6, minLevel + 1)) + line.substring(level);
                }
            }
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isGeminiSavedMemories(String title) {
        return title != null && title.trim().equalsIgnoreCase("Gemini Added Memories");
    }

    private static List<String> bullets(String content) {
        List<String> list = new ArrayList<>();
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("- ") || t.startsWith("* ")) {
                String v = t.substring(2).trim();
                if (!v.isEmpty()) list.add(v);
            }
        }
        return list;
    }

    static String humanize(String fileName) {
        String base = fileName;
        for (String ext : List.of(".instructions.md", ".mdc", ".md", ".txt")) {
            if (base.toLowerCase().endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
                break;
            }
        }
        if (base.startsWith(".")) base = base.substring(1);
        String spaced = base.replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isEmpty()) return fileName;
        if (spaced.equals(spaced.toUpperCase())) return base; // keep CLAUDE, AGENTS, GEMINI
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static String lastModified(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault()).format(TS);
        } catch (IOException e) {
            return null;
        }
    }

    static String hash(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}

package com.github.axiomate.agentic.ide.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.interop.MarkdownDocument;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.CommandContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.McpServerContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.MemoryContribution;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads plugin packages from disk: native Axiomate plugins ({@code axiomate-plugin.json}) and
 * Claude Code plugins ({@code .claude-plugin/plugin.json} with {@code commands/}, {@code agents/},
 * {@code skills/} and {@code .mcp.json}). Also extracts ZIP archives safely and resolves GitHub URLs.
 */
public final class PluginPackageReader {

    public static final String FORMAT_AXIOMATE = "axiomate";
    public static final String FORMAT_CLAUDE_CODE = "claude-code";

    private static final int MAX_ZIP_ENTRIES = 5_000;
    private static final long MAX_ZIP_BYTES = 100L * 1024 * 1024;

    /**
     * A plugin read from disk, ready to be previewed and installed.
     */
    public record PluginPackage(PluginManifest manifest, Path root, String format, List<String> warnings) {
    }

    /**
     * A download location resolved from a user-supplied URL.
     *
     * @param subPath folder inside the archive that contains the plugin, or null for the archive root
     */
    public record ResolvedUrl(URI archiveUri, String subPath) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PluginPackageReader() {
    }

    public static PluginPackage read(Path dir) throws IOException {
        return read(dir, 0);
    }

    private static PluginPackage read(Path dir, int depth) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("Not a plugin folder: " + dir);

        Path nativeManifest = dir.resolve(PluginManifest.FILE_NAME);
        if (Files.isRegularFile(nativeManifest)) {
            PluginManifest m = MAPPER.readValue(nativeManifest.toFile(), PluginManifest.class);
            String rawId = m.id() != null && !m.id().isBlank() ? m.id()
                    : (m.name() != null ? m.name() : dir.getFileName().toString());
            m = m.withId(sanitizeId(rawId)); // ids become folder names: never trust them as paths
            return new PluginPackage(m, dir, FORMAT_AXIOMATE, List.of());
        }

        Path claudeManifest = dir.resolve(".claude-plugin").resolve("plugin.json");
        if (Files.isRegularFile(claudeManifest)) {
            return readClaudePlugin(dir, claudeManifest);
        }

        Path marketplace = dir.resolve(".claude-plugin").resolve("marketplace.json");
        if (Files.isRegularFile(marketplace)) {
            JsonNode root = MAPPER.readTree(marketplace.toFile());
            List<String> names = new ArrayList<>();
            root.path("plugins").forEach(p -> names.add(p.path("name").asText() + " (" + p.path("source").asText() + ")"));
            throw new IOException("This folder is a Claude Code plugin marketplace, not a single plugin. "
                    + "Install one of its plugins by pointing at its folder: " + String.join(", ", names));
        }

        // GitHub archives wrap everything in a single top-level folder
        if (depth < 2) {
            try (Stream<Path> s = Files.list(dir)) {
                List<Path> children = s.filter(p -> !p.getFileName().toString().startsWith(".")
                        && !p.getFileName().toString().equals("__MACOSX")).toList();
                if (children.size() == 1 && Files.isDirectory(children.get(0))) {
                    return read(children.get(0), depth + 1);
                }
            }
        }
        throw new IOException("No " + PluginManifest.FILE_NAME + " or .claude-plugin/plugin.json found in " + dir);
    }

    private static PluginPackage readClaudePlugin(Path dir, Path manifestFile) throws IOException {
        JsonNode pj = MAPPER.readTree(manifestFile.toFile());
        List<String> warnings = new ArrayList<>();
        String name = pj.path("name").asText(dir.getFileName().toString());
        String author = pj.path("author").isObject() ? pj.path("author").path("name").asText("") : pj.path("author").asText("");
        List<String> keywords = new ArrayList<>();
        pj.path("keywords").forEach(k -> keywords.add(k.asText()));

        // Commands: default commands/ folder plus any extra paths listed in plugin.json
        List<CommandContribution> commands = new ArrayList<>();
        List<Path> commandPaths = new ArrayList<>(List.of(dir.resolve("commands")));
        addPaths(pj.path("commands"), dir, commandPaths);
        for (Path p : commandPaths) {
            for (Path md : markdownFiles(p)) {
                MarkdownDocument doc = MarkdownDocument.parse(Files.readString(md, StandardCharsets.UTF_8));
                String cmdName = commandName(p, md);
                commands.add(new CommandContribution(cmdName, firstNonBlank(doc.frontmatter("description"),
                        "Command from " + name), doc.getBody().strip()));
            }
        }

        // Agents become commands that run the task with the agent's system prompt
        List<Path> agentPaths = new ArrayList<>(List.of(dir.resolve("agents")));
        addPaths(pj.path("agents"), dir, agentPaths);
        for (Path p : agentPaths) {
            for (Path md : markdownFiles(p)) {
                MarkdownDocument doc = MarkdownDocument.parse(Files.readString(md, StandardCharsets.UTF_8));
                String agentName = firstNonBlank(doc.frontmatter("name"), stripExt(md.getFileName().toString()));
                String prompt = "Act as the '" + agentName + "' specialist agent.\n\n" + doc.getBody().strip()
                        + "\n\nTask: $ARGUMENTS";
                commands.add(new CommandContribution(agentName, firstNonBlank(doc.frontmatter("description"),
                        "Specialist agent from " + name), prompt));
            }
        }

        // Skills become long-term memories the agent retrieves when relevant
        List<MemoryContribution> memories = new ArrayList<>();
        Path skills = dir.resolve("skills");
        if (Files.isDirectory(skills)) {
            try (Stream<Path> s = Files.walk(skills, 3)) {
                for (Path skillFile : s.filter(f -> f.getFileName().toString().equals("SKILL.md")).sorted().toList()) {
                    MarkdownDocument doc = MarkdownDocument.parse(Files.readString(skillFile, StandardCharsets.UTF_8));
                    String skillName = firstNonBlank(doc.frontmatter("name"), skillFile.getParent().getFileName().toString());
                    String desc = doc.frontmatter("description");
                    String content = (desc != null ? desc + "\n\n" : "") + doc.getBody().strip();
                    memories.add(new MemoryContribution("Skill: " + skillName, content, "LONG_TERM", List.of("skill")));
                }
            }
        }

        // MCP servers: .mcp.json at the root, or inline / path in plugin.json
        List<McpServerContribution> servers = new ArrayList<>();
        JsonNode mcpNode = null;
        if (pj.path("mcpServers").isObject()) {
            mcpNode = pj.get("mcpServers");
        } else if (pj.path("mcpServers").isTextual()) {
            Path p = dir.resolve(pj.get("mcpServers").asText()).normalize();
            if (p.startsWith(dir) && Files.isRegularFile(p)) mcpNode = MAPPER.readTree(p.toFile());
        } else if (Files.isRegularFile(dir.resolve(".mcp.json"))) {
            mcpNode = MAPPER.readTree(dir.resolve(".mcp.json").toFile());
        }
        if (mcpNode != null) {
            JsonNode map = mcpNode.has("mcpServers") ? mcpNode.get("mcpServers") : mcpNode;
            for (Iterator<Map.Entry<String, JsonNode>> it = map.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode def = e.getValue();
                List<String> args = new ArrayList<>();
                def.path("args").forEach(a -> args.add(a.asText()));
                Map<String, String> env = new LinkedHashMap<>();
                def.path("env").fields().forEachRemaining(v -> env.put(v.getKey(), v.getValue().asText()));
                List<String> required = new ArrayList<>();
                env.forEach((k, v) -> {
                    if (v.matches("\\$\\{[A-Za-z0-9_]+}")) required.add(k);
                });
                String url = def.path("url").asText(null);
                servers.add(new McpServerContribution(e.getKey(), url != null && !def.has("command") ? "SSE" : "STDIO",
                        def.path("command").asText(null), args, env, url, "From Claude Code plugin " + name, required));
            }
        }

        if (Files.exists(dir.resolve("hooks")) || pj.has("hooks")) {
            warnings.add("Hooks are Claude Code specific and were not installed.");
        }
        if (commands.isEmpty() && memories.isEmpty() && servers.isEmpty()) {
            warnings.add("The plugin contains no commands, agents, skills or MCP servers that Axiomate can use.");
        }

        PluginManifest manifest = new PluginManifest(sanitizeId(name), name, pj.path("version").asText(null),
                pj.path("description").asText(""), author, "Claude Code Plugin", pj.path("homepage").asText(null),
                keywords, servers, memories, commands);
        return new PluginPackage(manifest, dir, FORMAT_CLAUDE_CODE, warnings);
    }

    private static void addPaths(JsonNode node, Path dir, List<Path> out) {
        if (node.isTextual()) {
            Path p = dir.resolve(node.asText()).normalize();
            if (p.startsWith(dir) && !out.contains(p)) out.add(p);
        } else if (node.isArray()) {
            node.forEach(n -> addPaths(n, dir, out));
        }
    }

    private static List<Path> markdownFiles(Path p) throws IOException {
        if (Files.isRegularFile(p) && p.toString().endsWith(".md")) return List.of(p);
        if (!Files.isDirectory(p)) return List.of();
        try (Stream<Path> s = Files.walk(p, 2)) {
            return s.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".md")).sorted().toList();
        }
    }

    /** commands/review.md -> "review"; commands/git/commit.md -> "git:commit" (Claude Code namespacing). */
    private static String commandName(Path base, Path file) {
        if (!Files.isDirectory(base)) return stripExt(file.getFileName().toString());
        Path rel = base.relativize(file);
        List<String> parts = new ArrayList<>();
        rel.forEach(p -> parts.add(p.toString()));
        parts.set(parts.size() - 1, stripExt(parts.get(parts.size() - 1)));
        return String.join(":", parts);
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /**
     * Turns a plugin id or name into a safe single folder name: lowercase letters, digits, '.', '_' and '-',
     * never starting or ending with '.' or '-' (so "." and ".." are impossible).
     */
    public static String sanitizeId(String raw) {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[-.]+|[-.]+$", "");
        return id.isEmpty() ? "plugin-" + Integer.toHexString(Objects.hashCode(raw)) : id;
    }

    // ------------------------------------------------------------------
    // Archives & URLs
    // ------------------------------------------------------------------

    /**
     * Extracts a ZIP archive into {@code target}, rejecting entries that escape the target folder.
     */
    public static void extractZip(InputStream in, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        int entries = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("Archive has too many entries");
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("Blocked unsafe archive entry: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_ZIP_BYTES) throw new IOException("Archive is larger than 100 MB");
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /**
     * Resolves a plugin URL: direct .zip links, GitHub repositories, and GitHub folders
     * ({@code https://github.com/owner/repo/tree/<ref>/<path>}).
     */
    public static ResolvedUrl resolveUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        URI uri = URI.create(u);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only https:// URLs are supported");
        }
        if ("github.com".equalsIgnoreCase(uri.getHost())) {
            String[] parts = uri.getPath().replaceFirst("^/", "").split("/");
            if (parts.length < 2) throw new IllegalArgumentException("Expected https://github.com/<owner>/<repo>");
            String owner = parts[0];
            String repo = parts[1];
            if (parts.length >= 4 && ("tree".equals(parts[2]) || "blob".equals(parts[2]))) {
                String ref = parts[3];
                String sub = parts.length > 4 ? String.join("/", Arrays.copyOfRange(parts, 4, parts.length)) : null;
                return new ResolvedUrl(URI.create("https://github.com/" + owner + "/" + repo + "/archive/" + ref + ".zip"), sub);
            }
            if (parts.length == 2) {
                return new ResolvedUrl(URI.create("https://github.com/" + owner + "/" + repo + "/archive/HEAD.zip"), null);
            }
        }
        if (uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return new ResolvedUrl(uri, null);
        }
        throw new IllegalArgumentException("Unsupported plugin URL. Use a .zip link or a GitHub repository/folder URL.");
    }
}

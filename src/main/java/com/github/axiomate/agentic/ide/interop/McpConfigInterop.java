package com.github.axiomate.agentic.ide.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.mcp.McpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Discovers MCP server definitions configured in other coding agents and converts them to Axiomate
 * {@link McpServerConfig}s, and writes Axiomate's MCP servers into other agents' config files.
 * <p>
 * JSON configs are merged (other keys and servers are preserved); Codex's TOML config is appended to.
 */
public class McpConfigInterop {

    private static final Logger log = LoggerFactory.getLogger(McpConfigInterop.class);

    public enum Format {
        /** {"mcpServers": {name: {command,args,env | url}}} */
        JSON_MCP_SERVERS,
        /** VS Code / Copilot: {"servers": {name: {type, command, args, env | url}}} */
        JSON_SERVERS,
        /** Windsurf / Antigravity use "serverUrl" for remote servers. */
        JSON_MCP_SERVERS_SERVER_URL,
        /** ~/.claude.json: top-level mcpServers plus projects[path].mcpServers */
        CLAUDE_USER_JSON,
        /** Codex config.toml: [mcp_servers.name] tables */
        CODEX_TOML
    }

    public record McpConfigFile(CodingAgent agent, MemoryScope scope, String path, Format format) {
        public Path resolve(Path projectDir, Path homeDir) {
            Path base = scope == MemoryScope.PROJECT ? projectDir : homeDir;
            return base == null ? null : base.resolve(path);
        }
    }

    public record DiscoveredServer(CodingAgent agent, Path file, McpServerConfig config, String note) {
    }

    public record ExportResult(Path file, List<String> written, List<String> skipped) {
    }

    private static final List<McpConfigFile> SOURCES = List.of(
            new McpConfigFile(CodingAgent.CLAUDE_CODE, MemoryScope.PROJECT, ".mcp.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.CLAUDE_CODE, MemoryScope.USER, ".claude.json", Format.CLAUDE_USER_JSON),
            new McpConfigFile(CodingAgent.CODEX, MemoryScope.PROJECT, ".codex/config.toml", Format.CODEX_TOML),
            new McpConfigFile(CodingAgent.CODEX, MemoryScope.USER, ".codex/config.toml", Format.CODEX_TOML),
            new McpConfigFile(CodingAgent.CURSOR, MemoryScope.PROJECT, ".cursor/mcp.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.CURSOR, MemoryScope.USER, ".cursor/mcp.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.ANTIGRAVITY, MemoryScope.USER, ".gemini/antigravity/mcp_config.json", Format.JSON_MCP_SERVERS_SERVER_URL),
            new McpConfigFile(CodingAgent.ANTIGRAVITY, MemoryScope.USER, ".gemini/config/mcp_config.json", Format.JSON_MCP_SERVERS_SERVER_URL),
            new McpConfigFile(CodingAgent.GEMINI_CLI, MemoryScope.PROJECT, ".gemini/settings.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.GEMINI_CLI, MemoryScope.USER, ".gemini/settings.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.WINDSURF, MemoryScope.USER, ".codeium/windsurf/mcp_config.json", Format.JSON_MCP_SERVERS_SERVER_URL),
            new McpConfigFile(CodingAgent.GITHUB_COPILOT, MemoryScope.PROJECT, ".vscode/mcp.json", Format.JSON_SERVERS),
            new McpConfigFile(CodingAgent.ROO_CODE, MemoryScope.PROJECT, ".roo/mcp.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.KIRO, MemoryScope.PROJECT, ".kiro/settings/mcp.json", Format.JSON_MCP_SERVERS),
            new McpConfigFile(CodingAgent.KIRO, MemoryScope.USER, ".kiro/settings/mcp.json", Format.JSON_MCP_SERVERS)
    );

    private static final Map<CodingAgent, McpConfigFile> EXPORT_TARGETS = new EnumMap<>(Map.of(
            CodingAgent.CLAUDE_CODE, SOURCES.get(0),
            CodingAgent.CODEX, SOURCES.get(3),
            CodingAgent.CURSOR, SOURCES.get(4),
            CodingAgent.ANTIGRAVITY, SOURCES.get(6),
            CodingAgent.GEMINI_CLI, SOURCES.get(8),
            CodingAgent.WINDSURF, SOURCES.get(10),
            CodingAgent.GITHUB_COPILOT, SOURCES.get(11),
            CodingAgent.ROO_CODE, SOURCES.get(12),
            CodingAgent.KIRO, SOURCES.get(13)
    ));

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path homeDir;

    public McpConfigInterop() {
        this(Paths.get(System.getProperty("user.home", ".")));
    }

    public McpConfigInterop(Path homeDir) {
        this.homeDir = homeDir;
    }

    public static List<McpConfigFile> sources() {
        return SOURCES;
    }

    public static Optional<McpConfigFile> exportTarget(CodingAgent agent) {
        return Optional.ofNullable(EXPORT_TARGETS.get(agent));
    }

    // ------------------------------------------------------------------
    // Discovery / import
    // ------------------------------------------------------------------

    public List<DiscoveredServer> discover(Path projectDir) {
        List<DiscoveredServer> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (McpConfigFile src : SOURCES) {
            Path file = src.resolve(projectDir, homeDir);
            if (file == null || !Files.isRegularFile(file) || !seen.add(file.toAbsolutePath().normalize())) continue;
            try {
                result.addAll(read(src, file, projectDir));
            } catch (Exception e) {
                log.warn("Could not read MCP config {}: {}", file, e.getMessage());
            }
        }
        return result;
    }

    List<DiscoveredServer> read(McpConfigFile src, Path file, Path projectDir) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        List<DiscoveredServer> list = new ArrayList<>();
        switch (src.format()) {
            case CODEX_TOML -> {
                Object servers = MiniToml.parse(text).get("mcp_servers");
                if (servers instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        if (e.getValue() instanceof Map<?, ?> def) {
                            list.add(fromToml(src.agent(), file, String.valueOf(e.getKey()), def));
                        }
                    }
                }
            }
            case CLAUDE_USER_JSON -> {
                JsonNode root = mapper.readTree(text);
                addJsonServers(list, src.agent(), file, root.path("mcpServers"));
                if (projectDir != null) {
                    String wanted = ProjectStateManager.normalizePath(projectDir.toFile());
                    JsonNode projects = root.path("projects");
                    for (Iterator<Map.Entry<String, JsonNode>> it = projects.fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> p = it.next();
                        if (p.getKey().replace('\\', '/').equalsIgnoreCase(wanted)) {
                            addJsonServers(list, src.agent(), file, p.getValue().path("mcpServers"));
                        }
                    }
                }
            }
            case JSON_SERVERS -> addJsonServers(list, src.agent(), file, mapper.readTree(text).path("servers"));
            default -> addJsonServers(list, src.agent(), file, mapper.readTree(text).path("mcpServers"));
        }
        return list;
    }

    private void addJsonServers(List<DiscoveredServer> out, CodingAgent agent, Path file, JsonNode servers) {
        if (servers == null || !servers.isObject()) return;
        for (Iterator<Map.Entry<String, JsonNode>> it = servers.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode def = e.getValue();
            McpServerConfig cfg = new McpServerConfig();
            cfg.setName(e.getKey());
            String note = null;
            String url = firstText(def, "url", "serverUrl", "httpUrl");
            if (def.hasNonNull("command")) {
                cfg.setTransport(McpTransport.STDIO);
                cfg.setCommand(def.get("command").asText());
                List<String> args = new ArrayList<>();
                def.path("args").forEach(a -> args.add(a.asText()));
                cfg.setArgs(args);
                Map<String, String> env = new LinkedHashMap<>();
                def.path("env").fields().forEachRemaining(v -> env.put(v.getKey(), v.getValue().asText()));
                cfg.setEnv(env);
            } else if (url != null) {
                cfg.setTransport(McpTransport.SSE);
                cfg.setUrl(url);
                String type = firstText(def, "type", "transport");
                if (def.has("httpUrl") || (type != null && type.toLowerCase().contains("http"))) {
                    note = "Streamable HTTP server imported as SSE";
                }
            } else {
                continue;
            }
            boolean disabled = def.path("disabled").asBoolean(false) || !def.path("enabled").asBoolean(true);
            cfg.setEnabled(!disabled);
            cfg.setDescription("Imported from " + agent.getDisplayName());
            out.add(new DiscoveredServer(agent, file, cfg, note));
        }
    }

    private DiscoveredServer fromToml(CodingAgent agent, Path file, String name, Map<?, ?> def) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setName(name);
        String note = null;
        if (def.get("command") != null) {
            cfg.setTransport(McpTransport.STDIO);
            cfg.setCommand(String.valueOf(def.get("command")));
            List<String> args = new ArrayList<>();
            if (def.get("args") instanceof List<?> l) {
                l.forEach(a -> args.add(String.valueOf(a)));
            }
            cfg.setArgs(args);
            Map<String, String> env = new LinkedHashMap<>();
            if (def.get("env") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
            }
            cfg.setEnv(env);
        } else if (def.get("url") != null) {
            cfg.setTransport(McpTransport.SSE);
            cfg.setUrl(String.valueOf(def.get("url")));
            note = "Streamable HTTP server imported as SSE";
        }
        cfg.setEnabled(!Boolean.FALSE.equals(def.get("enabled")));
        cfg.setDescription("Imported from " + agent.getDisplayName());
        return new DiscoveredServer(agent, file, cfg, note);
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.hasNonNull(k) && !node.get(k).asText().isBlank()) return node.get(k).asText();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Merges the given servers into the agent's MCP config file. Existing servers with the same name
     * are replaced when {@code overwrite} is true, otherwise skipped.
     */
    public ExportResult export(CodingAgent agent, Path projectDir, List<McpServerConfig> servers, boolean overwrite)
            throws IOException {
        McpConfigFile target = exportTarget(agent)
                .orElseThrow(() -> new IllegalArgumentException(agent.getDisplayName() + " has no file-based MCP config"));
        Path file = target.resolve(projectDir, homeDir);
        if (file == null) throw new IllegalArgumentException("No project directory for " + target.path());
        if (file.getParent() != null) Files.createDirectories(file.getParent());

        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (target.format() == Format.CODEX_TOML) {
            String existing = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            Object parsed = existing.isBlank() ? null : MiniToml.parse(existing).get("mcp_servers");
            Set<String> present = parsed instanceof Map<?, ?> m ? new HashSet<>(stringKeys(m)) : Set.of();
            String kept = existing;
            for (McpServerConfig s : servers) {
                if (present.contains(s.getName()) && overwrite) kept = removeTomlServer(kept, s.getName());
            }
            StringBuilder sb = new StringBuilder(kept.stripTrailing());
            for (McpServerConfig s : servers) {
                if (present.contains(s.getName()) && !overwrite) {
                    skipped.add(s.getName());
                    continue;
                }
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(toToml(s));
                written.add(s.getName());
            }
            writeAtomically(file, sb.append('\n').toString());
        } else {
            ObjectNode root = Files.exists(file) && Files.size(file) > 0
                    ? (ObjectNode) mapper.readTree(file.toFile())
                    : mapper.createObjectNode();
            String key = target.format() == Format.JSON_SERVERS ? "servers" : "mcpServers";
            ObjectNode serversNode = root.has(key) && root.get(key).isObject()
                    ? (ObjectNode) root.get(key)
                    : root.putObject(key);
            for (McpServerConfig s : servers) {
                if (serversNode.has(s.getName()) && !overwrite) {
                    skipped.add(s.getName());
                    continue;
                }
                serversNode.set(s.getName(), toJson(s, target.format()));
                written.add(s.getName());
            }
            writeAtomically(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        }
        log.info("Exported {} MCP server(s) to {} ({} skipped)", written.size(), file, skipped.size());
        return new ExportResult(file, written, skipped);
    }

    /** Another agent's config: a crash mid-write must not leave it half-written. */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "." + file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Removes a server's [mcp_servers.name] table and its sub-tables ([mcp_servers.name.env]) from Codex TOML. */
    static String removeTomlServer(String toml, String name) {
        java.util.regex.Pattern own = java.util.regex.Pattern.compile(
                "^\\s*\\[\\s*mcp_servers\\.(?:\"" + java.util.regex.Pattern.quote(name) + "\"|" + java.util.regex.Pattern.quote(name) + ")(?:\\.[^\\]]*)?\\s*]\\s*$");
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : toml.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("[")) skipping = own.matcher(line).matches();
            if (!skipping) out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n");
    }

    private ObjectNode toJson(McpServerConfig s, Format format) {
        ObjectNode n = mapper.createObjectNode();
        if (s.getTransport() == McpTransport.STDIO) {
            if (format == Format.JSON_SERVERS) n.put("type", "stdio");
            n.put("command", s.getCommand());
            ArrayNode args = n.putArray("args");
            s.getArgs().forEach(args::add);
            if (!s.getEnv().isEmpty()) {
                ObjectNode env = n.putObject("env");
                s.getEnv().forEach(env::put);
            }
        } else {
            if (format == Format.JSON_SERVERS || format == Format.JSON_MCP_SERVERS) n.put("type", "sse");
            n.put(format == Format.JSON_MCP_SERVERS_SERVER_URL ? "serverUrl" : "url", s.getUrl());
        }
        return n;
    }

    static String toToml(McpServerConfig s) {
        StringBuilder sb = new StringBuilder("[mcp_servers.").append(MiniToml.key(s.getName())).append("]\n");
        if (s.getTransport() == McpTransport.STDIO) {
            sb.append("command = ").append(MiniToml.string(s.getCommand())).append('\n');
            sb.append("args = [");
            for (int i = 0; i < s.getArgs().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(MiniToml.string(s.getArgs().get(i)));
            }
            sb.append("]\n");
            if (!s.getEnv().isEmpty()) {
                sb.append("env = { ");
                int i = 0;
                for (Map.Entry<String, String> e : s.getEnv().entrySet()) {
                    if (i++ > 0) sb.append(", ");
                    sb.append(MiniToml.key(e.getKey())).append(" = ").append(MiniToml.string(e.getValue()));
                }
                sb.append(" }\n");
            }
        } else {
            sb.append("url = ").append(MiniToml.string(s.getUrl())).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static List<String> stringKeys(Map<?, ?> m) {
        List<String> keys = new ArrayList<>();
        m.keySet().forEach(k -> keys.add(String.valueOf(k)));
        return keys;
    }
}

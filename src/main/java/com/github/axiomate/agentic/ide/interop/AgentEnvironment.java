package com.github.axiomate.agentic.ide.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves where coding agents keep their user-level configuration, honoring the environment
 * variables the agents themselves read, and reads the agent settings that change which instruction
 * files they load:
 * <ul>
 *   <li>Claude Code: {@code CLAUDE_CONFIG_DIR} replaces {@code ~/.claude}</li>
 *   <li>OpenAI Codex: {@code CODEX_HOME} replaces {@code ~/.codex}; {@code project_doc_fallback_filenames}
 *       in {@code config.toml} adds project instruction file names</li>
 *   <li>Gemini CLI: {@code GEMINI_CLI_HOME} replaces the home directory (config lives in
 *       {@code $GEMINI_CLI_HOME/.gemini}); {@code context.fileName} in {@code settings.json} renames the
 *       context file</li>
 * </ul>
 */
public final class AgentEnvironment {

    public static final String CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR";
    public static final String CODEX_HOME = "CODEX_HOME";
    public static final String GEMINI_CLI_HOME = "GEMINI_CLI_HOME";
    public static final String DEFAULT_GEMINI_CONTEXT_FILE = "GEMINI.md";

    private static final Logger log = LoggerFactory.getLogger(AgentEnvironment.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_CONFIG_BYTES = 1024 * 1024;

    private final Path homeDir;
    private final Map<String, String> env;

    private AgentEnvironment(Path homeDir, Map<String, String> env) {
        this.homeDir = homeDir;
        this.env = env == null ? Map.of() : env;
    }

    /** The current user's home directory and process environment. */
    public static AgentEnvironment system() {
        return system(Paths.get(System.getProperty("user.home", ".")));
    }

    /** An explicit home directory with the process environment. */
    public static AgentEnvironment system(Path homeDir) {
        return new AgentEnvironment(homeDir, System.getenv());
    }

    /** An explicit home directory with no environment overrides (deterministic; used by tests). */
    public static AgentEnvironment isolated(Path homeDir) {
        return new AgentEnvironment(homeDir, Map.of());
    }

    public static AgentEnvironment of(Path homeDir, Map<String, String> env) {
        return new AgentEnvironment(homeDir, Map.copyOf(env));
    }

    public Path homeDir() {
        return homeDir;
    }

    /** Claude Code's config directory: {@code $CLAUDE_CONFIG_DIR} or {@code ~/.claude}. */
    public Path claudeHome() {
        return dirFromEnv(CLAUDE_CONFIG_DIR, homeDir.resolve(".claude"));
    }

    /** Codex's home directory: {@code $CODEX_HOME} or {@code ~/.codex}. */
    public Path codexHome() {
        return dirFromEnv(CODEX_HOME, homeDir.resolve(".codex"));
    }

    /** Gemini CLI's config directory: {@code $GEMINI_CLI_HOME/.gemini} or {@code ~/.gemini}. */
    public Path geminiHome() {
        return dirFromEnv(GEMINI_CLI_HOME, homeDir).resolve(".gemini");
    }

    /**
     * Extra project instruction file names Codex falls back to when a directory has no AGENTS.md
     * ({@code project_doc_fallback_filenames} in {@code $CODEX_HOME/config.toml}).
     */
    public List<String> codexFallbackFilenames() {
        Path config = codexHome().resolve("config.toml");
        String text = readConfig(config);
        if (text == null) return List.of();
        try {
            return fileNames(MiniToml.parse(text).get("project_doc_fallback_filenames"));
        } catch (RuntimeException e) {
            log.warn("Could not parse {}: {}", config, e.getMessage());
            return List.of();
        }
    }

    /**
     * The context file names Gemini CLI loads ({@code context.fileName}, or the legacy top-level
     * {@code contextFileName}). Workspace settings override user settings; defaults to GEMINI.md.
     */
    public List<String> geminiContextFileNames(Path projectDir) {
        List<String> names = null;
        if (projectDir != null) {
            names = geminiContextSetting(projectDir.resolve(".gemini").resolve("settings.json"));
        }
        if (names == null) {
            names = geminiContextSetting(geminiHome().resolve("settings.json"));
        }
        return names == null || names.isEmpty() ? List.of(DEFAULT_GEMINI_CONTEXT_FILE) : names;
    }

    private List<String> geminiContextSetting(Path settings) {
        String text = readConfig(settings);
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(stripJsonComments(text));
            if (root == null) return null;
            JsonNode node = root.path("context").path("fileName");
            if (node.isMissingNode() || node.isNull()) node = root.path("contextFileName");
            if (node.isTextual()) return fileNames(List.of(node.asText()));
            if (node.isArray()) {
                List<Object> values = new ArrayList<>();
                node.forEach(n -> {
                    if (n.isTextual()) values.add(n.asText());
                });
                return fileNames(values);
            }
            return null;
        } catch (Exception e) {
            log.warn("Could not parse {}: {}", settings, e.getMessage());
            return null;
        }
    }

    /**
     * Gemini CLI tolerates {@code //} and {@code /* *}{@code /} comments in settings.json; strip them
     * (outside string literals) before handing the text to a strict JSON parser.
     */
    static String stripJsonComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                sb.append('\n');
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Keeps plain file names only; anything that could escape the directory it is looked up in is dropped. */
    private static List<String> fileNames(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) continue;
            String name = s.strip();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
            if (!name.matches("[^/\\\\:*?\"<>|]+")) continue;
            if (!out.contains(name)) out.add(name);
        }
        return out;
    }

    private Path dirFromEnv(String var, Path fallback) {
        String v = env.get(var);
        if (v == null || v.isBlank()) return fallback;
        Path p = expandHome(v.strip());
        return p != null ? p : fallback;
    }

    /** Expands a leading {@code ~} to the home directory and makes the path absolute; null when invalid. */
    public Path expandHome(String raw) {
        try {
            if (raw.equals("~")) return homeDir;
            if (raw.startsWith("~/") || raw.startsWith("~\\")) {
                return homeDir.resolve(raw.substring(2)).toAbsolutePath().normalize();
            }
            return Paths.get(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String readConfig(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_CONFIG_BYTES) return null;
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Could not read {}: {}", file, e.getMessage());
            return null;
        }
    }
}

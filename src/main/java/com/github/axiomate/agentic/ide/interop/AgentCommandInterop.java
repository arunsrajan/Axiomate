package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers slash commands / workflows defined for other coding agents so they can be used from the
 * Axiomate chat: Claude Code commands, Codex prompts, Cursor and Roo commands, Antigravity and Windsurf
 * workflows, and Gemini CLI TOML commands.
 */
public class AgentCommandInterop {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandInterop.class);
    private static final long MAX_FILE_BYTES = 128 * 1024;

    public record CommandLocation(CodingAgent agent, MemoryScope scope, String path, String suffix) {
    }

    private static final List<CommandLocation> LOCATIONS = List.of(
            new CommandLocation(CodingAgent.CLAUDE_CODE, MemoryScope.PROJECT, ".claude/commands", ".md"),
            new CommandLocation(CodingAgent.CLAUDE_CODE, MemoryScope.USER, ".claude/commands", ".md"),
            new CommandLocation(CodingAgent.CODEX, MemoryScope.USER, ".codex/prompts", ".md"),
            new CommandLocation(CodingAgent.CURSOR, MemoryScope.PROJECT, ".cursor/commands", ".md"),
            new CommandLocation(CodingAgent.CURSOR, MemoryScope.USER, ".cursor/commands", ".md"),
            new CommandLocation(CodingAgent.ANTIGRAVITY, MemoryScope.PROJECT, ".agents/workflows", ".md"),
            new CommandLocation(CodingAgent.ANTIGRAVITY, MemoryScope.PROJECT, ".agent/workflows", ".md"),
            new CommandLocation(CodingAgent.ANTIGRAVITY, MemoryScope.USER, ".gemini/config/global_workflows", ".md"),
            new CommandLocation(CodingAgent.WINDSURF, MemoryScope.PROJECT, ".windsurf/workflows", ".md"),
            new CommandLocation(CodingAgent.GEMINI_CLI, MemoryScope.PROJECT, ".gemini/commands", ".toml"),
            new CommandLocation(CodingAgent.GEMINI_CLI, MemoryScope.USER, ".gemini/commands", ".toml"),
            new CommandLocation(CodingAgent.ROO_CODE, MemoryScope.PROJECT, ".roo/commands", ".md"),
            new CommandLocation(CodingAgent.CLINE, MemoryScope.PROJECT, ".clinerules/workflows", ".md")
    );

    private final Path homeDir;

    public AgentCommandInterop() {
        this(Paths.get(System.getProperty("user.home", ".")));
    }

    public AgentCommandInterop(Path homeDir) {
        this.homeDir = homeDir;
    }

    public static String sourceKey(CodingAgent agent) {
        return "agent:" + agent.getId();
    }

    /**
     * Discovers commands; project-level commands override user-level ones with the same name.
     */
    public List<SlashCommand> discover(Path projectDir) {
        Map<String, SlashCommand> byName = new LinkedHashMap<>();
        // user scope first so project scope overrides
        for (MemoryScope scope : List.of(MemoryScope.USER, MemoryScope.PROJECT)) {
            for (CommandLocation loc : LOCATIONS) {
                if (loc.scope() != scope) continue;
                Path base = scope == MemoryScope.PROJECT ? projectDir : homeDir;
                if (base == null) continue;
                Path dir = base.resolve(loc.path());
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> s = Files.walk(dir, 3)) {
                    for (Path f : s.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(loc.suffix())).sorted().toList()) {
                        SlashCommand cmd = read(loc, dir, f);
                        if (cmd != null) byName.put(cmd.name(), cmd);
                    }
                } catch (IOException e) {
                    log.warn("Could not scan commands in {}: {}", dir, e.getMessage());
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    private SlashCommand read(CommandLocation loc, Path base, Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return null;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = commandName(base, file, loc.suffix());
            String label = loc.agent().getDisplayName();
            if (loc.suffix().equals(".toml")) {
                Map<String, Object> toml = MiniToml.parse(text);
                Object prompt = toml.get("prompt");
                if (prompt == null) return null;
                String template = String.valueOf(prompt).replace("{{args}}", "$ARGUMENTS");
                Object desc = toml.get("description");
                return new SlashCommand(name, desc != null ? desc + " (" + label + ")" : label + " command", template,
                        sourceKey(loc.agent()));
            }
            MarkdownDocument doc = MarkdownDocument.parse(text);
            String body = doc.getBody().strip();
            if (body.isEmpty()) return null;
            String desc = doc.frontmatter("description");
            return new SlashCommand(name, desc != null ? desc + " (" + label + ")" : label + " command", body,
                    sourceKey(loc.agent()));
        } catch (Exception e) {
            log.warn("Skipping command file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static String commandName(Path base, Path file, String suffix) {
        String rel = base.relativize(file).toString().replace('\\', '/');
        rel = rel.substring(0, rel.length() - suffix.length());
        return rel.replace('/', ':');
    }
}

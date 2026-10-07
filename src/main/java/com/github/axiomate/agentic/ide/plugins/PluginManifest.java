package com.github.axiomate.agentic.ide.plugins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.mcp.McpTransport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes an Axiomate plugin ({@code axiomate-plugin.json}). A plugin bundles any combination of
 * MCP servers, agent memories (rules / knowledge / skills) and slash commands.
 * <p>
 * Claude Code plugins ({@code .claude-plugin/plugin.json}) are converted to this model on install.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record PluginManifest(
        String id,
        String name,
        String version,
        String description,
        String author,
        String category,
        String homepage,
        List<String> keywords,
        List<McpServerContribution> mcpServers,
        List<MemoryContribution> memories,
        List<CommandContribution> commands
) {

    public static final String FILE_NAME = "axiomate-plugin.json";

    public PluginManifest {
        keywords = keywords != null ? List.copyOf(keywords) : List.of();
        mcpServers = mcpServers != null ? List.copyOf(mcpServers) : List.of();
        memories = memories != null ? List.copyOf(memories) : List.of();
        commands = commands != null ? List.copyOf(commands) : List.of();
        version = (version == null || version.isBlank()) ? "0.0.0" : version;
        category = (category == null || category.isBlank()) ? "General" : category;
        description = description != null ? description : "";
        author = author != null ? author : "";
    }

    public String displayName() {
        return name != null && !name.isBlank() ? name : id;
    }

    public PluginManifest withId(String newId) {
        return new PluginManifest(newId, name, version, description, author, category, homepage, keywords,
                mcpServers, memories, commands);
    }

    /**
     * Summary of what the plugin contributes, e.g. "2 MCP servers · 3 memories · 1 command".
     */
    public String contributionSummary() {
        List<String> parts = new ArrayList<>();
        if (!mcpServers.isEmpty()) parts.add(mcpServers.size() + " MCP server" + (mcpServers.size() == 1 ? "" : "s"));
        if (!memories.isEmpty()) parts.add(memories.size() + " memor" + (memories.size() == 1 ? "y" : "ies"));
        if (!commands.isEmpty()) parts.add(commands.size() + " command" + (commands.size() == 1 ? "" : "s"));
        return parts.isEmpty() ? "No contributions" : String.join(" · ", parts);
    }

    /**
     * An MCP server started by the plugin. {@code ${PLUGIN_ROOT}} / {@code ${CLAUDE_PLUGIN_ROOT}} expand to the
     * plugin's install directory and {@code ${PROJECT_ROOT}} to the project open at install time.
     *
     * @param requiredEnv environment variables the user should provide (e.g. API tokens)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record McpServerContribution(String name, String transport, String command, List<String> args,
                                        Map<String, String> env, String url, String description,
                                        List<String> requiredEnv) {
        public McpServerContribution {
            args = args != null ? List.copyOf(args) : List.of();
            env = env != null ? new LinkedHashMap<>(env) : new LinkedHashMap<>();
            requiredEnv = requiredEnv != null ? List.copyOf(requiredEnv) : List.of();
        }

        public static McpServerContribution stdio(String name, String description, String command,
                                                  List<String> args, List<String> requiredEnv) {
            return new McpServerContribution(name, "STDIO", command, args, null, null, description, requiredEnv);
        }

        public boolean isRemote() {
            return (command == null || command.isBlank()) && url != null && !url.isBlank();
        }

        /**
         * One-line description of what will run, shown to the user before installing.
         */
        public String launchSummary() {
            if (isRemote()) return url;
            return (command + " " + String.join(" ", args)).trim();
        }

        public McpServerConfig toConfig(String serverName, Map<String, String> vars, Map<String, String> envValues) {
            McpServerConfig cfg = new McpServerConfig();
            cfg.setName(serverName);
            if (isRemote()) {
                cfg.setTransport(McpTransport.SSE);
                cfg.setUrl(expand(url, vars));
            } else {
                cfg.setTransport(McpTransport.STDIO);
                cfg.setCommand(expand(command, vars));
                List<String> expandedArgs = new ArrayList<>();
                for (String a : args) expandedArgs.add(expand(a, vars));
                cfg.setArgs(expandedArgs);
            }
            Map<String, String> finalEnv = new LinkedHashMap<>();
            env.forEach((k, v) -> finalEnv.put(k, expand(v, vars)));
            if (envValues != null) {
                envValues.forEach((k, v) -> {
                    if (v != null && !v.isBlank()) finalEnv.put(k, v);
                });
            }
            // Unset placeholders would override the inherited environment with an empty value
            finalEnv.values().removeIf(v -> v == null || v.isBlank() || v.matches("\\$\\{[A-Za-z0-9_]+}"));
            cfg.setEnv(finalEnv);
            cfg.setDescription(description != null ? description : "");
            return cfg;
        }

        static String expand(String value, Map<String, String> vars) {
            if (value == null) return null;
            String out = value;
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue());
            }
            return out;
        }
    }

    /**
     * A memory (rule, knowledge or skill) added to the agentic memory store while the plugin is enabled.
     *
     * @param type a {@link com.github.axiomate.agentic.ide.agent.memory.MemoryType} name; defaults to LONG_TERM
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MemoryContribution(String title, String content, String type, List<String> tags) {
        public MemoryContribution {
            tags = tags != null ? List.copyOf(tags) : List.of();
        }
    }

    /**
     * A slash command. {@code $ARGUMENTS} expands to everything typed after the command, {@code $1..$9}
     * to individual words.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record CommandContribution(String name, String description, String prompt) {
    }
}

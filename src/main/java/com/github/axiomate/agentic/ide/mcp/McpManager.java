package com.github.axiomate.agentic.ide.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.interop.McpConfigInterop;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Singleton manager coordinating Model Context Protocol (MCP) servers,
 * client connections, tool discovery, and registration with the AI Agent.
 */
public class McpManager {

    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static McpManager instance;

    private final ObjectMapper mapper;
    private final Path configPath;
    private final List<McpServerConfig> serverConfigs = new CopyOnWriteArrayList<>();
    private final Map<String, McpClient> activeClients = new ConcurrentHashMap<>();
    private final Map<String, List<McpTool>> serverTools = new ConcurrentHashMap<>();
    private final Map<String, List<McpClient.McpPromptDefinition>> serverPrompts = new ConcurrentHashMap<>();
    private final List<Runnable> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private McpManager() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        Path dir = com.github.axiomate.agentic.ide.config.ConfigManager.getAppDirectory();
        this.configPath = dir.resolve("mcp_servers.json");
        loadConfigs();

        // Listen to active project changes so project-bound MCP servers retarget and reconnect
        ProjectManager.getInstance().addProjectChangeListener(this::onProjectChanged);

        // Connect enabled servers asynchronously
        CompletableFuture.runAsync(this::connectAllEnabled);

        // Register JVM shutdown hook to clean up child processes
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll));
    }

    public static synchronized McpManager getInstance() {
        if (instance == null) {
            instance = new McpManager();
        }
        return instance;
    }

    private void loadConfigs() {
        if (Files.exists(configPath)) {
            try {
                List<McpServerConfig> loaded = mapper.readValue(configPath.toFile(), new TypeReference<List<McpServerConfig>>() {});
                serverConfigs.clear();
                serverConfigs.addAll(loaded);
                log.info("Loaded {} MCP server configurations from {}", serverConfigs.size(), configPath);
            } catch (Exception e) {
                // Keep the unreadable file: the defaults saved below must not replace the user's servers
                Path backup = com.github.axiomate.agentic.ide.config.AtomicFiles.backupUnreadable(configPath);
                log.warn("Could not read MCP server config from {} ({}); kept a copy at {}",
                        configPath, com.github.axiomate.agentic.ide.config.AtomicFiles.readFailureReason(e), backup);
                log.debug("MCP server config read failure", e);
            }
        }

        if (serverConfigs.isEmpty()) {
            initDefaultServers();
        } else {
            File currentProj = ProjectManager.getInstance().getCurrentProjectDirectory();
            if (currentProj != null) {
                retargetStoredConfigs(currentProj.toPath());
            }
        }
    }

    public synchronized boolean retargetStoredConfigs(Path projectPath) {
        if (projectPath == null) return false;
        boolean anyModified = false;
        for (McpServerConfig cfg : serverConfigs) {
            if (McpConfigInterop.isProjectBound(cfg)) {
                boolean modified = McpConfigInterop.retargetToProject(cfg, projectPath, null);
                if (modified) {
                    anyModified = true;
                }
            }
        }
        if (anyModified) {
            log.info("Retargeted MCP server configurations to project: {}", projectPath);
            saveConfigs();
        }
        return anyModified;
    }

    public synchronized void onProjectChanged(File newProjectDir) {
        if (newProjectDir == null || !newProjectDir.isDirectory()) return;
        Path projectPath = newProjectDir.toPath();
        retargetStoredConfigs(projectPath);

        // Reconnect active project-bound servers so they use the new project
        for (McpServerConfig cfg : serverConfigs) {
            if (McpConfigInterop.isProjectBound(cfg)) {
                if (activeClients.containsKey(cfg.getName())) {
                    log.info("Project switched to '{}': reconnecting project-bound MCP server '{}'",
                            newProjectDir.getName(), cfg.getName());
                    disconnectServer(cfg.getName());
                    if (cfg.isEnabled()) {
                        CompletableFuture.runAsync(() -> connectServer(cfg));
                    }
                }
            }
        }
    }

    private void initDefaultServers() {
        // Sample MCP server configurations
        McpServerConfig demoServer = new McpServerConfig("echo", "node", List.of("-e", """
                const readline = require('readline');
                const rl = readline.createInterface({input: process.stdin, output: process.stdout, terminal: false});
                rl.on('line', (line) => {
                  try {
                    const req = JSON.parse(line);
                    if (req.method === 'initialize') {
                      console.log(JSON.stringify({jsonrpc: '2.0', id: req.id, result: {protocolVersion: '2024-11-05', capabilities: {tools: {}}, serverInfo: {name: 'EchoServer'}}}));
                    } else if (req.method === 'tools/list') {
                      console.log(JSON.stringify({jsonrpc: '2.0', id: req.id, result: {tools: [{name: 'echo_ping', description: 'Replies with pong and input text', inputSchema: {type: 'object', properties: {message: {type: 'string'}}}}]}}));
                    } else if (req.method === 'tools/call') {
                      console.log(JSON.stringify({jsonrpc: '2.0', id: req.id, result: {content: [{type: 'text', text: 'PONG: ' + JSON.stringify(req.params.arguments)}]}}));
                    }
                  } catch(e) {}
                });
                """));
        demoServer.setDescription("Built-in Node.js echo MCP test server");
        demoServer.setEnabled(false); // Disabled by default until user enables or tests
        serverConfigs.add(demoServer);
        saveConfigs();
    }

    public synchronized void saveConfigs() {
        try {
            if (configPath.getParent() != null && !Files.exists(configPath.getParent())) {
                Files.createDirectories(configPath.getParent());
            }
            // May hold tokens in server env vars: owner-only, never half-written
            com.github.axiomate.agentic.ide.config.AtomicFiles.writeJson(mapper, configPath, new ArrayList<>(serverConfigs), true);
            log.info("Saved MCP server configs to {}", configPath);
        } catch (IOException e) {
            log.error("Failed to save MCP configs", e);
        }
        notifyListeners();
    }

    public List<McpServerConfig> getServerConfigs() {
        return new ArrayList<>(serverConfigs);
    }

    public synchronized void addServer(McpServerConfig config) {
        File currentProj = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (currentProj != null && McpConfigInterop.isProjectBound(config)) {
            McpConfigInterop.retargetToProject(config, currentProj.toPath(), null);
        }
        serverConfigs.removeIf(s -> s.getName().equalsIgnoreCase(config.getName()));
        serverConfigs.add(config);
        saveConfigs();
        if (config.isEnabled()) {
            CompletableFuture.runAsync(() -> connectServer(config));
        }
    }

    public synchronized void removeServer(String name) {
        serverConfigs.removeIf(s -> s.getName().equalsIgnoreCase(name));
        disconnectServer(name);
        saveConfigs();
    }

    public synchronized void updateServer(McpServerConfig config) {
        addServer(config);
    }

    public void connectAllEnabled() {
        for (McpServerConfig cfg : serverConfigs) {
            if (cfg.isEnabled()) {
                connectServer(cfg);
            }
        }
    }

    public static String sourceKey(String serverName) {
        return "mcp:" + serverName;
    }

    public void connectServer(McpServerConfig cfg) {
        disconnectServer(cfg.getName());
        McpClient client = new McpClient(cfg);
        try {
            client.connect();
            activeClients.put(cfg.getName(), client);

            List<McpClient.McpToolDefinition> tools = client.listTools();
            List<McpTool> wrappedTools = new ArrayList<>();
            for (McpClient.McpToolDefinition def : tools) {
                McpTool mcpTool = new McpTool(cfg.getName(), def.name(), def.description(), def.inputSchema(), client);
                wrappedTools.add(mcpTool);
                // Register with every agent service, not just the one active right now (often the demo service)
                AgentManager.getInstance().registerTool(mcpTool);
            }
            serverTools.put(cfg.getName(), wrappedTools);

            List<McpClient.McpPromptDefinition> prompts = client.listPrompts();
            serverPrompts.put(cfg.getName(), prompts);

            // Register slash commands for both tools and prompts into SlashCommandRegistry
            registerSlashCommands(cfg, tools, prompts, client);

            log.info("MCP server '{}' connected: discovered {} tools, {} prompts", cfg.getName(), tools.size(), prompts.size());
            notifyListeners();
        } catch (Exception e) {
            log.error("Failed to connect to MCP server '{}'", cfg.getName(), e);
            activeClients.remove(cfg.getName(), client);
            client.close(); // don't leave the server process running
        }
    }

    private void registerSlashCommands(McpServerConfig cfg, List<McpClient.McpToolDefinition> tools,
                                       List<McpClient.McpPromptDefinition> prompts, McpClient client) {
        SlashCommandRegistry reg = SlashCommandRegistry.getInstance();
        String source = sourceKey(cfg.getName());

        // Register tools as slash commands: /<server>:<tool>
        for (McpClient.McpToolDefinition def : tools) {
            String cmdName = cfg.getName() + ":" + def.name();
            String desc = (def.description() != null && !def.description().isBlank())
                    ? def.description() + " (" + cfg.getName() + ")"
                    : "MCP tool from " + cfg.getName();
            String template = "Use the MCP tool '" + def.name() + "' from server '" + cfg.getName() + "'.";
            reg.register(new SlashCommand(cmdName, desc, template, source));
        }

        // Register prompts as slash commands: /<server>:<prompt>
        for (McpClient.McpPromptDefinition promptDef : prompts) {
            String cmdName = cfg.getName() + ":" + promptDef.name();
            String desc = (promptDef.description() != null && !promptDef.description().isBlank())
                    ? promptDef.description() + " (" + cfg.getName() + ")"
                    : "MCP prompt from " + cfg.getName();
            String fallbackTemplate = "Execute MCP prompt '" + promptDef.name() + "' from server '" + cfg.getName() + "'.";

            reg.register(new SlashCommand(
                    cmdName,
                    desc,
                    fallbackTemplate,
                    source,
                    null,
                    rawArgs -> {
                        Map<String, String> argsMap = parsePromptArguments(promptDef, rawArgs);
                        try {
                            String rendered = client.getPrompt(promptDef.name(), argsMap);
                            if (rendered != null && !rendered.isBlank()) {
                                return rendered;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to get MCP prompt '{}' from '{}': {}", promptDef.name(), cfg.getName(), e.getMessage());
                        }
                        return fallbackTemplate + (rawArgs != null && !rawArgs.isBlank() ? "\n\n" + rawArgs : "");
                    }
            ));
        }
    }

    static Map<String, String> parsePromptArguments(McpClient.McpPromptDefinition promptDef, String rawArgs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawArgs == null || rawArgs.isBlank()) {
            return map;
        }
        String trimmed = rawArgs.trim();
        List<McpClient.McpPromptArgument> expected = promptDef.arguments();
        if (expected == null || expected.isEmpty()) {
            map.put("input", trimmed);
            return map;
        }
        if (expected.size() == 1) {
            map.put(expected.get(0).name(), trimmed);
            return map;
        }
        if (trimmed.contains("=")) {
            Matcher m = Pattern.compile("([a-zA-Z0-9_-]+)=(?:\"([^\"]*)\"|'([^']*)'|(\\S+))").matcher(trimmed);
            boolean found = false;
            while (m.find()) {
                String k = m.group(1);
                String v = m.group(2) != null ? m.group(2) : (m.group(3) != null ? m.group(3) : m.group(4));
                map.put(k, v);
                found = true;
            }
            if (found) return map;
        }
        String[] parts = trimmed.split("\\s+");
        for (int i = 0; i < expected.size(); i++) {
            if (i < parts.length) {
                if (i == expected.size() - 1) {
                    map.put(expected.get(i).name(), String.join(" ", Arrays.copyOfRange(parts, i, parts.length)));
                } else {
                    map.put(expected.get(i).name(), parts[i]);
                }
            }
        }
        return map;
    }

    public void disconnectServer(String name) {
        McpClient client = activeClients.remove(name);
        if (client != null) {
            client.close();
        }
        // Its tools go too: calling one would quietly start the disconnected server again
        List<McpTool> tools = serverTools.remove(name);
        if (tools != null) {
            for (McpTool t : tools) AgentManager.getInstance().unregisterTool(t.getName());
        }
        serverPrompts.remove(name);
        SlashCommandRegistry.getInstance().unregisterSource(sourceKey(name));
        notifyListeners();
    }

    public List<McpTool> getDiscoveredTools(String serverName) {
        return serverTools.getOrDefault(serverName, Collections.emptyList());
    }

    public List<McpClient.McpPromptDefinition> getDiscoveredPrompts(String serverName) {
        return serverPrompts.getOrDefault(serverName, Collections.emptyList());
    }

    public CompletableFuture<List<McpClient.McpToolDefinition>> testConnection(McpServerConfig cfg) {
        return CompletableFuture.supplyAsync(() -> {
            try (McpClient testClient = new McpClient(cfg)) {
                testClient.connect();
                return testClient.listTools();
            } catch (Exception e) {
                throw new CompletionException("Connection test failed: " + e.getMessage(), e);
            }
        });
    }

    public boolean isConnected(String serverName) {
        McpClient client = activeClients.get(serverName);
        return client != null && client.isConnected();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable r : changeListeners) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("Error notifying MCP change listener", e);
            }
        }
    }

    public void closeAll() {
        for (String name : activeClients.keySet()) {
            SlashCommandRegistry.getInstance().unregisterSource(sourceKey(name));
        }
        for (McpClient client : activeClients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
        activeClients.clear();
        serverTools.clear();
        serverPrompts.clear();
    }
}


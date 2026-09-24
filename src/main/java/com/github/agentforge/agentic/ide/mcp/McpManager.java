package com.github.agentforge.agentic.ide.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.agentforge.agentic.ide.agent.AgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

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
    private final List<Runnable> changeListeners = new ArrayList<>();

    private McpManager() {
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        Path dir = com.github.agentforge.agentic.ide.config.ConfigManager.getAppDirectory();
        this.configPath = dir.resolve("mcp_servers.json");
        loadConfigs();

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
                log.error("Failed to load MCP server config from {}", configPath, e);
            }
        }

        if (serverConfigs.isEmpty()) {
            initDefaultServers();
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
            mapper.writeValue(configPath.toFile(), serverConfigs);
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

    public void connectServer(McpServerConfig cfg) {
        disconnectServer(cfg.getName());
        try {
            McpClient client = new McpClient(cfg);
            client.connect();
            activeClients.put(cfg.getName(), client);

            List<McpClient.McpToolDefinition> tools = client.listTools();
            List<McpTool> wrappedTools = new ArrayList<>();
            for (McpClient.McpToolDefinition def : tools) {
                McpTool mcpTool = new McpTool(cfg.getName(), def.name(), def.description(), def.inputSchema(), client);
                wrappedTools.add(mcpTool);
                // Register with agent tool manager
                AgentManager.getInstance().getActiveService().registerTool(mcpTool);
            }
            serverTools.put(cfg.getName(), wrappedTools);
            log.info("MCP server '{}' connected: discovered {} tools", cfg.getName(), tools.size());
            notifyListeners();
        } catch (Exception e) {
            log.error("Failed to connect to MCP server '{}'", cfg.getName(), e);
        }
    }

    public void disconnectServer(String name) {
        McpClient client = activeClients.remove(name);
        if (client != null) {
            client.close();
        }
        serverTools.remove(name);
        notifyListeners();
    }

    public List<McpTool> getDiscoveredTools(String serverName) {
        return serverTools.getOrDefault(serverName, Collections.emptyList());
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
        for (McpClient client : activeClients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {}
        }
        activeClients.clear();
        serverTools.clear();
    }
}

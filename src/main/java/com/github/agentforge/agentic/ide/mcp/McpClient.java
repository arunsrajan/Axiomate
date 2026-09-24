package com.github.agentforge.agentic.ide.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standard JSON-RPC 2.0 client for communicating with MCP (Model Context Protocol) servers
 * over Stdio or SSE transport.
 */
public class McpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpServerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);

    // Stdio process state
    private Process process;
    private BufferedWriter processWriter;
    private BufferedReader processReader;
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();

    // SSE / HTTP client state
    private HttpClient httpClient;

    private boolean connected = false;

    public McpClient(McpServerConfig config) {
        this.config = config;
    }

    public synchronized void connect() throws Exception {
        if (connected) return;

        if (config.getTransport() == McpTransport.STDIO) {
            connectStdio();
        } else {
            connectSse();
        }
        connected = true;

        // Perform MCP initialize handshake
        initializeHandshake();
    }

    private void connectStdio() throws IOException {
        List<String> commandList = new ArrayList<>();
        commandList.add(config.getCommand());
        commandList.addAll(config.getArgs());

        ProcessBuilder pb = new ProcessBuilder(commandList);
        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            pb.environment().putAll(config.getEnv());
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        this.process = pb.start();
        this.processWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.processReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        readerExecutor.submit(this::listenStdioOutput);
        log.info("Started MCP server process for '{}': {}", config.getName(), commandList);
    }

    private void connectSse() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Initialized HTTP/SSE MCP client for '{}': {}", config.getName(), config.getUrl());
    }

    private void listenStdioOutput() {
        try {
            String line;
            while ((line = processReader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode json = mapper.readTree(line);
                    if (json.has("id")) {
                        int id = json.path("id").asInt();
                        CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                        if (future != null) {
                            future.complete(json);
                        }
                    }
                } catch (Exception e) {
                    log.debug("MCP non-json output: {}", line);
                }
            }
        } catch (IOException e) {
            log.info("MCP server '{}' stdio stream closed", config.getName());
        }
    }

    private void initializeHandshake() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "AgentForgeIDE");
        clientInfo.put("version", "1.0.0");

        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("roots").put("listChanged", true);

        JsonNode response = sendRequest("initialize", params);
        if (response.has("error")) {
            throw new RuntimeException("MCP initialize failed: " + response.path("error").path("message").asText());
        }

        // Send notifications/initialized
        sendNotification("notifications/initialized", mapper.createObjectNode());
        log.info("MCP server '{}' initialized successfully", config.getName());
    }

    public List<McpToolDefinition> listTools() throws Exception {
        if (!connected) {
            connect();
        }

        JsonNode response = sendRequest("tools/list", mapper.createObjectNode());
        if (response.has("error")) {
            throw new RuntimeException("MCP tools/list failed: " + response.path("error").path("message").asText());
        }

        List<McpToolDefinition> tools = new ArrayList<>();
        JsonNode toolsArray = response.path("result").path("tools");
        if (toolsArray.isArray()) {
            for (JsonNode tNode : toolsArray) {
                String name = tNode.path("name").asText();
                String desc = tNode.path("description").asText("");
                JsonNode schema = tNode.path("inputSchema");
                tools.add(new McpToolDefinition(name, desc, schema));
            }
        }
        return tools;
    }

    public String callTool(String toolName, String argumentsJson) throws Exception {
        if (!connected) {
            connect();
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);

        if (argumentsJson != null && !argumentsJson.isBlank()) {
            try {
                JsonNode argsNode = mapper.readTree(argumentsJson);
                params.set("arguments", argsNode);
            } catch (Exception e) {
                params.putObject("arguments").put("input", argumentsJson);
            }
        } else {
            params.putObject("arguments");
        }

        JsonNode response = sendRequest("tools/call", params);
        if (response.has("error")) {
            return "ERROR: MCP tool error: " + response.path("error").path("message").asText();
        }

        JsonNode content = response.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if (item.has("text")) {
                    sb.append(item.path("text").asText()).append("\n");
                }
            }
            return sb.toString().trim();
        }

        return response.path("result").toString();
    }

    private JsonNode sendRequest(String method, JsonNode params) throws Exception {
        int id = requestIdCounter.getAndIncrement();

        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        if (config.getTransport() == McpTransport.STDIO) {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingRequests.put(id, future);

            String line = mapper.writeValueAsString(req);
            synchronized (processWriter) {
                processWriter.write(line);
                processWriter.newLine();
                processWriter.flush();
            }

            try {
                return future.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pendingRequests.remove(id);
                throw new TimeoutException("MCP request timed out for method: " + method);
            }
        } else {
            // HTTP / SSE Post
            String body = mapper.writeValueAsString(req);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        }
    }

    private void sendNotification(String method, JsonNode params) throws Exception {
        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", method);
        notif.set("params", params);

        if (config.getTransport() == McpTransport.STDIO && processWriter != null) {
            String line = mapper.writeValueAsString(notif);
            synchronized (processWriter) {
                processWriter.write(line);
                processWriter.newLine();
                processWriter.flush();
            }
        }
    }

    public boolean isConnected() {
        return connected && (process == null || process.isAlive());
    }

    @Override
    public synchronized void close() {
        connected = false;
        if (process != null) {
            try {
                process.destroy();
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            } finally {
                process.destroyForcibly();
            }
        }
        readerExecutor.shutdownNow();
    }

    public record McpToolDefinition(String name, String description, JsonNode inputSchema) {}
}

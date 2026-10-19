package com.github.axiomate.agentic.ide.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private volatile BufferedWriter processWriter;
    private BufferedReader processReader;
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    // SSE / HTTP client state
    private HttpClient httpClient;

    private volatile boolean connected = false;
    /** Capabilities declared by the server at initialize (tools, prompts, etc.) */
    private volatile JsonNode serverCapabilities;
    /** Session id an HTTP (streamable) server assigns at initialize; sent back with every later request. */
    private volatile String httpSessionId;

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

        // Perform MCP initialize handshake; a failed handshake must not leave a half-connected client behind
        try {
            initializeHandshake();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private void connectStdio() throws IOException {
        File projectDir = com.github.axiomate.agentic.ide.util.ProjectManager.getInstance().getCurrentProjectDirectory();
        java.nio.file.Path projPath = projectDir != null ? projectDir.toPath() : null;
        List<String> effectiveArgs = com.github.axiomate.agentic.ide.interop.McpConfigInterop.resolveArgs(config, projPath);
        List<String> commandList = commandLine(config.getCommand(), effectiveArgs,
                com.github.axiomate.agentic.ide.util.OSUtils.isWindows());

        ProcessBuilder pb = new ProcessBuilder(commandList);
        if (projectDir != null && projectDir.isDirectory()) {
            pb.directory(projectDir);
        }
        Map<String, String> effectiveEnv = com.github.axiomate.agentic.ide.interop.McpConfigInterop.resolveEnv(config, projPath);
        if (effectiveEnv != null && !effectiveEnv.isEmpty()) {
            pb.environment().putAll(effectiveEnv);
        }
        if (projectDir != null) {
            String p = projectDir.getAbsolutePath();
            pb.environment().put("PROJECT_ROOT", p);
            pb.environment().put("PROJECT_DIR", p);
            pb.environment().put("WORKSPACE_FOLDER", p);
            pb.environment().put("WORKSPACE_ROOT", p);
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        this.process = pb.start();
        this.processWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.processReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // A reader thread per connection, so the client can reconnect after the server exits or a failed start
        Thread reader = new Thread(this::listenStdioOutput, "mcp-" + config.getName());
        reader.setDaemon(true);
        reader.start();
        log.info("Started MCP server process for '{}' in {}: {}", config.getName(),
                projectDir != null ? projectDir.getAbsolutePath() : "<default>", commandList);
    }

    private void connectSse() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Initialized HTTP/SSE MCP client for '{}': {}", config.getName(), config.getUrl());
    }

    /**
     * The process to start for a stdio server. On Windows, launchers such as npx, uvx or pnpm are .cmd scripts that
     * cannot be started directly, so commands without an .exe go through cmd.exe.
     */
    static List<String> commandLine(String command, List<String> args, boolean windows) {
        List<String> list = new ArrayList<>();
        String lower = command == null ? "" : command.toLowerCase(java.util.Locale.ROOT);
        if (windows && !lower.endsWith(".exe") && !lower.endsWith(".com")) {
            list.add("cmd.exe");
            list.add("/c");
        }
        list.add(command);
        if (args != null) list.addAll(args);
        return list;
    }

    private void listenStdioOutput() {
        try {
            String line;
            while ((line = processReader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode json;
                try {
                    json = mapper.readTree(line);
                } catch (Exception e) {
                    log.debug("MCP non-json output: {}", line);
                    continue;
                }
                handleIncoming(json);
            }
        } catch (IOException e) {
            log.info("MCP server '{}' stdio stream closed", config.getName());
        } finally {
            // The server is gone: fail waiting calls now instead of after their timeout
            connected = false;
            IOException gone = new IOException("MCP server '" + config.getName() + "' exited");
            pendingRequests.values().forEach(f -> f.completeExceptionally(gone));
            pendingRequests.clear();
        }
    }

    /**
     * A message from the server: a response to one of our requests, or a request/notification of its own.
     * Server requests carry a "method" and must be answered; they are never responses to our requests.
     */
    void handleIncoming(JsonNode json) {
        if (json.has("method")) {
            if (json.has("id") && !json.get("id").isNull()) {
                answerServerRequest(json);
            }
            return; // notification
        }
        if (json.has("id")) {
            CompletableFuture<JsonNode> future = pendingRequests.remove(json.path("id").asInt());
            if (future != null) {
                future.complete(json);
            }
        }
    }

    private void answerServerRequest(JsonNode request) {
        ObjectNode reply = mapper.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", request.get("id"));
        String method = request.path("method").asText();
        switch (method) {
            case "ping" -> reply.putObject("result");
            case "roots/list" -> {
                // We advertise roots: the open project is the one root
                ArrayNode roots = reply.putObject("result").putArray("roots");
                java.io.File dir = com.github.axiomate.agentic.ide.util.ProjectManager.getInstance().getCurrentProjectDirectory();
                if (dir != null) {
                    ObjectNode root = roots.addObject();
                    root.put("uri", dir.toURI().toString());
                    root.put("name", dir.getName());
                }
            }
            default -> {
                ObjectNode error = reply.putObject("error");
                error.put("code", -32601);
                error.put("message", "Method not supported by this client: " + method);
            }
        }
        try {
            writeLine(mapper.writeValueAsString(reply));
        } catch (IOException e) {
            log.debug("Could not answer MCP server request {}: {}", method, e.getMessage());
        }
    }

    private void writeLine(String line) throws IOException {
        BufferedWriter writer = processWriter; // close() may clear the field concurrently
        if (writer == null) throw new IOException("MCP server '" + config.getName() + "' is not running");
        synchronized (writer) {
            writer.write(line);
            writer.newLine();
            writer.flush();
        }
    }

    private void initializeHandshake() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "AxiomateIDE");
        clientInfo.put("version", "1.0.0");

        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("roots").put("listChanged", true);

        JsonNode response = sendRequest("initialize", params);
        if (response.has("error")) {
            throw new RuntimeException("MCP initialize failed: " + response.path("error").path("message").asText());
        }
        this.serverCapabilities = response.path("result").path("capabilities");

        // Send notifications/initialized
        sendNotification("notifications/initialized", mapper.createObjectNode());
        log.info("MCP server '{}' initialized successfully (capabilities: tools={}, prompts={})",
                config.getName(), supportsTools(), supportsPrompts());
    }

    public boolean supportsPrompts() {
        return serverCapabilities != null && serverCapabilities.has("prompts");
    }

    public boolean supportsTools() {
        return serverCapabilities == null || serverCapabilities.has("tools");
    }

    public List<McpPromptDefinition> listPrompts() {
        if (!connected) {
            try {
                connect();
            } catch (Exception e) {
                log.debug("Could not connect to MCP server '{}' for prompts: {}", config.getName(), e.getMessage());
                return Collections.emptyList();
            }
        }
        if (!supportsPrompts()) {
            return Collections.emptyList();
        }
        try {
            JsonNode response = sendRequest("prompts/list", mapper.createObjectNode());
            if (response.has("error")) {
                log.debug("MCP prompts/list returned error on '{}': {}", config.getName(),
                        response.path("error").path("message").asText());
                return Collections.emptyList();
            }
            List<McpPromptDefinition> prompts = new ArrayList<>();
            JsonNode promptsArray = response.path("result").path("prompts");
            if (promptsArray.isArray()) {
                for (JsonNode pNode : promptsArray) {
                    String name = pNode.path("name").asText();
                    String desc = pNode.path("description").asText("");
                    List<McpPromptArgument> args = new ArrayList<>();
                    JsonNode argsArray = pNode.path("arguments");
                    if (argsArray.isArray()) {
                        for (JsonNode aNode : argsArray) {
                            args.add(new McpPromptArgument(
                                    aNode.path("name").asText(),
                                    aNode.path("description").asText(""),
                                    aNode.path("required").asBoolean(false)
                            ));
                        }
                    }
                    prompts.add(new McpPromptDefinition(name, desc, args));
                }
            }
            return prompts;
        } catch (Exception e) {
            log.debug("Failed to list prompts for MCP server '{}': {}", config.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public String getPrompt(String promptName, Map<String, String> arguments) throws Exception {
        if (!connected) {
            connect();
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("name", promptName);
        if (arguments != null && !arguments.isEmpty()) {
            ObjectNode argsNode = params.putObject("arguments");
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                argsNode.put(entry.getKey(), entry.getValue());
            }
        }
        JsonNode response = sendRequest("prompts/get", params);
        if (response.has("error")) {
            throw new IOException("MCP prompts/get error: " + response.path("error").path("message").asText());
        }
        JsonNode result = response.path("result");
        JsonNode messages = result.path("messages");
        if (messages.isArray() && !messages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode msg : messages) {
                JsonNode content = msg.path("content");
                if (content.isTextual()) {
                    sb.append(content.asText()).append("\n\n");
                } else if (content.has("text")) {
                    sb.append(content.path("text").asText()).append("\n\n");
                } else if (content.isArray()) {
                    for (JsonNode item : content) {
                        if (item.has("text")) {
                            sb.append(item.path("text").asText()).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
            String text = sb.toString().trim();
            if (!text.isEmpty()) return text;
        }
        if (result.has("description") && !result.path("description").asText().isBlank()) {
            return result.path("description").asText();
        }
        return result.toString();
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
        boolean isError = response.path("result").path("isError").asBoolean(false);
        if (content.isArray() && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if (item.has("text")) {
                    sb.append(item.path("text").asText()).append("\n");
                } else if (item.has("type")) {
                    sb.append("[").append(item.path("type").asText()).append(" content]\n"); // e.g. an image
                }
            }
            String text = sb.toString().trim();
            return isError ? "ERROR: " + text : text;
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

            writeLine(mapper.writeValueAsString(req));

            try {
                return future.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                pendingRequests.remove(id);
                throw new TimeoutException("MCP request timed out for method: " + method);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException(e.getCause() != null ? e.getCause().getMessage() : e.getMessage(), e);
            }
        } else {
            HttpResponse<String> resp = postHttp(mapper.writeValueAsString(req));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("MCP server '" + config.getName() + "' returned HTTP " + resp.statusCode() + ": " + resp.body());
            }
            resp.headers().firstValue("Mcp-Session-Id").ifPresent(sid -> httpSessionId = sid);
            String type = resp.headers().firstValue("Content-Type").orElse("");
            return type.contains("text/event-stream") ? responseFromEventStream(resp.body(), id) : mapper.readTree(resp.body());
        }
    }

    /** Streamable HTTP: JSON or an event stream may answer; the session id comes back with every request. */
    private HttpResponse<String> postHttp(String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30));
        if (httpSessionId != null) request.header("Mcp-Session-Id", httpSessionId);
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The JSON-RPC response with the given id among the events of an event-stream body. */
    JsonNode responseFromEventStream(String body, int id) throws IOException {
        StringBuilder data = new StringBuilder();
        for (String line : (body + "\n\n").split("\r?\n", -1)) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode msg = mapper.readTree(data.toString());
                data.setLength(0);
                if (!msg.has("method") && msg.path("id").asInt(-1) == id) return msg;
            }
        }
        throw new IOException("MCP server '" + config.getName() + "' sent no response for request " + id);
    }

    private void sendNotification(String method, JsonNode params) throws Exception {
        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", method);
        notif.set("params", params);

        if (config.getTransport() == McpTransport.STDIO) {
            writeLine(mapper.writeValueAsString(notif));
        } else if (httpClient != null) {
            postHttp(mapper.writeValueAsString(notif)); // 202 Accepted, no body
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
        process = null;
        processWriter = null;
        httpSessionId = null;
        serverCapabilities = null;
    }

    public record McpToolDefinition(String name, String description, JsonNode inputSchema) {}

    public record McpPromptArgument(String name, String description, boolean required) {}

    public record McpPromptDefinition(String name, String description, List<McpPromptArgument> arguments) {}
}


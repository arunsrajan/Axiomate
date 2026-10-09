package com.github.axiomate.agentic.ide.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class McpFixesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A stdio MCP server that, before answering tools/list, sends its own roots/list request using the same id as
     * the client's pending request, and reports the client's answer in the tool description.
     * With "exit" as argument it dies on tools/call.
     */
    static final String SERVER = """
            import java.io.*;
            public class FakeMcp {
                public static void main(String[] a) throws Exception {
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = in.readLine()) != null) {
                        String id = line.replaceAll(".*\\"id\\":(\\\\d+).*", "$1");
                        if (line.contains("\\"initialize\\"")) {
                            System.out.println("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":" + id + ",\\"result\\":{\\"protocolVersion\\":\\"2024-11-05\\",\\"capabilities\\":{}}}");
                        } else if (line.contains("\\"tools/list\\"")) {
                            System.out.println("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":" + id + ",\\"method\\":\\"roots/list\\"}");
                            System.out.flush();
                            String answer = in.readLine();
                            String ok = answer != null && answer.contains("\\"roots\\"") && !answer.contains("\\"method\\"") ? "answered" : "missing";
                            System.out.println("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":" + id + ",\\"result\\":{\\"tools\\":[{\\"name\\":\\"probe\\",\\"description\\":\\"roots " + ok + "\\",\\"inputSchema\\":{}}]}}");
                        } else if (line.contains("\\"tools/call\\"")) {
                            if (a.length > 0) System.exit(3);
                            System.out.println("{\\"jsonrpc\\":\\"2.0\\",\\"id\\":" + id + ",\\"result\\":{\\"isError\\":true,\\"content\\":[{\\"type\\":\\"text\\",\\"text\\":\\"bad input\\"}]}}");
                        }
                        System.out.flush();
                    }
                }
            }
            """;

    private McpServerConfig stdioServer(Path dir, boolean exitOnCall) throws Exception {
        Path src = dir.resolve("FakeMcp.java");
        Files.writeString(src, SERVER);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> args = exitOnCall ? List.of(src.toString(), "exit") : List.of(src.toString());
        return new McpServerConfig("fake server.v1", java, args);
    }

    @Test
    @DisplayName("Server requests are answered, never mistaken for responses; tool errors are marked")
    void serverRequestsAreAnswered(@TempDir Path dir) throws Exception {
        try (McpClient client = new McpClient(stdioServer(dir, false))) {
            client.connect();
            List<McpClient.McpToolDefinition> tools = client.listTools();
            assertEquals(1, tools.size(), "the roots/list request did not complete tools/list early");
            assertEquals("roots answered", tools.get(0).description());
            assertEquals("ERROR: bad input", client.callTool("probe", "{}"));
        }
    }

    @Test
    @DisplayName("Calls fail at once when the server exits, and the client can reconnect")
    void serverExitFailsFast(@TempDir Path dir) throws Exception {
        try (McpClient client = new McpClient(stdioServer(dir, true))) {
            client.connect();
            long start = System.currentTimeMillis();
            assertThrows(Exception.class, () -> client.callTool("probe", "{}"));
            assertTrue(System.currentTimeMillis() - start < 10_000, "not the 30 s request timeout");
            assertFalse(client.isConnected());
            client.connect(); // a new process and reader
            assertEquals(1, client.listTools().size());
        }
    }

    @Test
    @DisplayName("Tool names are valid for model APIs; Windows launchers go through cmd.exe")
    void namesAndLaunchers() {
        assertEquals("mcp_fake_server_v1_probe", McpTool.toolName("fake server.v1", "probe"));
        String longName = McpTool.toolName("a".repeat(50), "b".repeat(50));
        assertTrue(longName.length() <= 64 && longName.matches("[A-Za-z0-9_-]+"), longName);
        assertNotEquals(longName, McpTool.toolName("a".repeat(50), "b".repeat(49) + "c"), "still unique");

        assertEquals(List.of("cmd.exe", "/c", "npx", "-y", "server"), McpClient.commandLine("npx", List.of("-y", "server"), true));
        assertEquals(List.of("C:\\node\\node.exe", "x.js"), McpClient.commandLine("C:\\node\\node.exe", List.of("x.js"), true));
        assertEquals(List.of("npx", "-y"), McpClient.commandLine("npx", List.of("-y"), false));
    }

    @Test
    @DisplayName("Streamable HTTP: Accept header, session id and event-stream replies")
    void streamableHttp() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            JsonNode req = MAPPER.readTree(ex.getRequestBody().readAllBytes());
            String accept = ex.getRequestHeaders().getFirst("Accept");
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            String method = req.path("method").asText();
            seen.add(method + " sid=" + sid);
            byte[] body;
            if (accept == null || !accept.contains("text/event-stream")) {
                ex.sendResponseHeaders(406, -1);
                ex.close();
                return;
            }
            if (method.equals("initialize")) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "sess-42");
                ex.getResponseHeaders().add("Content-Type", "application/json");
                body = ("{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":{\"protocolVersion\":\"2024-11-05\"}}").getBytes(StandardCharsets.UTF_8);
            } else if (method.startsWith("notifications/")) {
                ex.sendResponseHeaders(202, -1);
                ex.close();
                return;
            } else {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                body = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                        + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":{\"tools\":[{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{}}]}}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            McpServerConfig cfg = new McpServerConfig("remote", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            try (McpClient client = new McpClient(cfg)) {
                client.connect();
                List<McpClient.McpToolDefinition> tools = client.listTools();
                assertEquals("search", tools.get(0).name());
            }
            assertEquals(List.of("initialize sid=null", "notifications/initialized sid=sess-42", "tools/list sid=sess-42"), seen);
        } finally {
            server.stop(0);
        }
    }
}

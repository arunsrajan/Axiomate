package com.github.axiomate.agentic.ide.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.interop.McpConfigInterop.DiscoveredServer;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.mcp.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigInteropTest {

    @TempDir
    Path tmp;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("MiniToml parses tables, quoted keys, arrays, inline tables and comments")
    void miniToml() {
        Map<String, Object> doc = MiniToml.parse("""
                model = "gpt-5-codex" # trailing comment
                [mcp_servers.github]
                command = "npx"
                args = [
                  "-y",
                  "@modelcontextprotocol/server-github", # comment in array
                ]
                env = { GITHUB_TOKEN = "abc", "X-Y" = 'lit\\eral' }
                startup_timeout_sec = 20

                [mcp_servers."my server".env]
                KEY = "v"

                [[profiles]]
                name = "a"
                [[profiles]]
                name = "b"
                """);
        assertEquals("gpt-5-codex", doc.get("model"));
        Map<?, ?> servers = (Map<?, ?>) doc.get("mcp_servers");
        Map<?, ?> gh = (Map<?, ?>) servers.get("github");
        assertEquals(List.of("-y", "@modelcontextprotocol/server-github"), gh.get("args"));
        assertEquals("abc", ((Map<?, ?>) gh.get("env")).get("GITHUB_TOKEN"));
        assertEquals("lit\\eral", ((Map<?, ?>) gh.get("env")).get("X-Y"));
        assertEquals(20L, gh.get("startup_timeout_sec"));
        assertEquals("v", ((Map<?, ?>) ((Map<?, ?>) servers.get("my server")).get("env")).get("KEY"));
        assertEquals(2, ((List<?>) doc.get("profiles")).size());
    }

    @Test
    @DisplayName("Discovers MCP servers from Claude Code, Cursor, Codex TOML, VS Code and Windsurf configs")
    void discover() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        write(project.resolve(".mcp.json"), """
                {"mcpServers": {"filesystem": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."]}}}
                """);
        write(project.resolve(".cursor/mcp.json"), """
                {"mcpServers": {"remote-docs": {"url": "https://docs.example.com/sse"}}}
                """);
        write(project.resolve(".vscode/mcp.json"), """
                {"servers": {"playwright": {"type": "stdio", "command": "npx", "args": ["@playwright/mcp@latest"]}}}
                """);
        write(home.resolve(".codex/config.toml"), """
                [mcp_servers.context7]
                command = "npx"
                args = ["-y", "@upstash/context7-mcp"]
                env = { API_KEY = "k" }
                """);
        write(home.resolve(".codeium/windsurf/mcp_config.json"), """
                {"mcpServers": {"linear": {"serverUrl": "https://mcp.linear.app/sse", "disabled": true}}}
                """);
        write(home.resolve(".claude.json"), """
                {"mcpServers": {"global-tool": {"command": "uvx", "args": ["tool"]}},
                 "projects": {"%s": {"mcpServers": {"proj-tool": {"command": "node", "args": ["s.js"]}}}}}
                """.formatted(project.toFile().getCanonicalPath().replace('\\', '/')));

        List<DiscoveredServer> found = new McpConfigInterop(home).discover(project);
        Map<String, DiscoveredServer> byName = new java.util.HashMap<>();
        found.forEach(d -> byName.put(d.config().getName(), d));

        assertEquals(CodingAgent.CLAUDE_CODE, byName.get("filesystem").agent());
        assertEquals(McpTransport.STDIO, byName.get("filesystem").config().getTransport());
        assertEquals(McpTransport.SSE, byName.get("remote-docs").config().getTransport());
        assertEquals(CodingAgent.GITHUB_COPILOT, byName.get("playwright").agent());
        assertEquals("k", byName.get("context7").config().getEnv().get("API_KEY"));
        assertFalse(byName.get("linear").config().isEnabled(), "disabled flag is honoured");
        assertTrue(byName.containsKey("global-tool"));
        assertTrue(byName.containsKey("proj-tool"), "per-project servers in ~/.claude.json are matched by path");
    }

    @Test
    @DisplayName("Export merges into JSON configs without clobbering, and appends to Codex TOML")
    void export() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        write(project.resolve(".mcp.json"), """
                {"mcpServers": {"existing": {"command": "keep"}}, "otherKey": true}
                """);
        write(home.resolve(".codex/config.toml"), "model = \"o3\"\n\n[mcp_servers.existing]\ncommand = \"keep\"\n");

        McpServerConfig stdio = new McpServerConfig("github", "npx", List.of("-y", "@modelcontextprotocol/server-github"));
        stdio.setEnv(Map.of("GITHUB_TOKEN", "t"));
        McpServerConfig sse = new McpServerConfig("docs", "https://docs.example.com/sse");
        McpServerConfig dup = new McpServerConfig("existing", "other", List.of());

        McpConfigInterop interop = new McpConfigInterop(home);
        var claude = interop.export(CodingAgent.CLAUDE_CODE, project, List.of(stdio, sse, dup), false);
        assertEquals(List.of("github", "docs"), claude.written());
        assertEquals(List.of("existing"), claude.skipped());
        JsonNode root = new ObjectMapper().readTree(project.resolve(".mcp.json").toFile());
        assertTrue(root.path("otherKey").asBoolean());
        assertEquals("keep", root.path("mcpServers").path("existing").path("command").asText());
        assertEquals("sse", root.path("mcpServers").path("docs").path("type").asText());

        var codex = interop.export(CodingAgent.CODEX, project, List.of(stdio, dup), false);
        assertEquals(List.of("github"), codex.written());
        String toml = Files.readString(home.resolve(".codex/config.toml"));
        assertTrue(toml.startsWith("model = \"o3\""));
        Map<?, ?> servers = (Map<?, ?>) MiniToml.parse(toml).get("mcp_servers");
        assertEquals("t", ((Map<?, ?>) ((Map<?, ?>) servers.get("github")).get("env")).get("GITHUB_TOKEN"));

        interop.export(CodingAgent.WINDSURF, project, List.of(sse), true);
        JsonNode ws = new ObjectMapper().readTree(home.resolve(".codeium/windsurf/mcp_config.json").toFile());
        assertEquals("https://docs.example.com/sse", ws.path("mcpServers").path("docs").path("serverUrl").asText());

        // Round-trip: what we exported can be discovered again
        assertTrue(interop.discover(project).stream().anyMatch(d -> d.config().getName().equals("github")
                && d.agent() == CodingAgent.CLAUDE_CODE));
    }
}

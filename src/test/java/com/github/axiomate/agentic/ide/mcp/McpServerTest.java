package com.github.axiomate.agentic.ide.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    @Test
    @DisplayName("McpServerConfig should serialize and deserialize JSON correctly")
    void testMcpServerConfigJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        McpServerConfig config = new McpServerConfig("sqlite", "uvx", List.of("mcp-server-sqlite", "--db-path", "test.db"));
        config.setEnv(Map.of("DEBUG", "1"));
        config.setDescription("SQLite Database MCP Server");

        String json = mapper.writeValueAsString(config);
        McpServerConfig deserialized = mapper.readValue(json, McpServerConfig.class);

        assertEquals("sqlite", deserialized.getName());
        assertEquals(McpTransport.STDIO, deserialized.getTransport());
        assertEquals("uvx", deserialized.getCommand());
        assertEquals(3, deserialized.getArgs().size());
        assertEquals("1", deserialized.getEnv().get("DEBUG"));
        assertTrue(deserialized.isEnabled());
    }

    @Test
    @DisplayName("McpTool should wrap remote tool metadata into AgentTool interface")
    void testMcpToolWrapping() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");

        McpTool tool = new McpTool("database", "execute_sql", "Executes a SELECT query", schema, null);

        assertEquals("mcp_database_execute_sql", tool.getName());
        assertTrue(tool.getDescription().contains("database"));
        assertTrue(tool.getDescription().contains("execute_sql"));
        assertTrue(tool.getDescription().contains("query"));

        AgentManager.getInstance().registerTool(tool);
        assertTrue(AgentManager.getInstance().getActiveService().getRegisteredTools()
                .stream().anyMatch(t -> t.getName().equals("mcp_database_execute_sql")));
    }

    @Test
    @DisplayName("McpManager should add and remove server configurations")
    void testMcpManagerAddRemove() {
        McpManager manager = McpManager.getInstance();
        int initialCount = manager.getServerConfigs().size();

        McpServerConfig testConfig = new McpServerConfig("test_server", "node", List.of("server.js"));
        testConfig.setEnabled(false); // don't spawn process in unit test
        manager.addServer(testConfig);

        assertEquals(initialCount + 1, manager.getServerConfigs().size());
        assertTrue(manager.getServerConfigs().stream().anyMatch(s -> s.getName().equals("test_server")));

        manager.removeServer("test_server");
        assertEquals(initialCount, manager.getServerConfigs().size());
    }

    @Test
    @DisplayName("MCP tools and prompts should be searchable via slash command autocomplete and resolve correctly")
    void testMcpSlashCommandIntegration() {
        SlashCommandRegistry reg = SlashCommandRegistry.getInstance();
        String source = McpManager.sourceKey("mock_db");

        // Tool slash command
        reg.register(new SlashCommand("mock_db:run_query", "Run a SQL query (mock_db)",
                "Use the MCP tool 'run_query' from server 'mock_db'.", source));

        // Prompt slash command with dynamic expander
        McpClient.McpPromptDefinition promptDef = new McpClient.McpPromptDefinition("explain_schema", "Explain table schema",
                List.of(new McpClient.McpPromptArgument("table", "Table name", true)));

        reg.register(new SlashCommand(
                "mock_db:explain_schema",
                "Explain table schema (mock_db)",
                "Execute MCP prompt 'explain_schema' from server 'mock_db'.",
                source,
                null,
                rawArgs -> {
                    Map<String, String> parsed = McpManager.parsePromptArguments(promptDef, rawArgs);
                    return "Schema for " + parsed.getOrDefault("table", "unknown") + " table with full index summary";
                }
        ));

        // 1. Direct find by full name
        SlashCommand cmd = reg.find("mock_db:run_query");
        assertNotNull(cmd);
        assertEquals("mock_db:run_query", cmd.name());
        assertEquals("Use the MCP tool 'run_query' from server 'mock_db'.\n\nSELECT 1", cmd.expand("SELECT 1"));

        // 2. Unambiguous find by suffix
        SlashCommand bySuffix = reg.find("run_query");
        assertNotNull(bySuffix);
        assertEquals("mock_db:run_query", bySuffix.name());

        // 3. Autocomplete with prefix "mock_db"
        List<SlashCommand> byPrefix = reg.complete("mock_db");
        assertTrue(byPrefix.stream().anyMatch(c -> c.name().equals("mock_db:run_query")));
        assertTrue(byPrefix.stream().anyMatch(c -> c.name().equals("mock_db:explain_schema")));

        // 4. Autocomplete with suffix query "query" or "explain"
        List<SlashCommand> byQuery = reg.complete("query");
        assertTrue(byQuery.stream().anyMatch(c -> c.name().equals("mock_db:run_query")));

        // 5. Dynamic prompt expansion
        SlashCommand promptCmd = reg.find("mock_db:explain_schema");
        assertNotNull(promptCmd);
        assertEquals("Schema for users table with full index summary", promptCmd.expand("users"));

        // 6. Unregister source on disconnect
        int removed = reg.unregisterSource(source);
        assertEquals(2, removed);
        assertNull(reg.find("mock_db:run_query"));
        assertNull(reg.find("mock_db:explain_schema"));
    }

    @Test
    @DisplayName("parsePromptArguments should handle single args, key=value pairs, and positional text")
    void testParsePromptArguments() {
        // Single argument
        McpClient.McpPromptDefinition single = new McpClient.McpPromptDefinition("test", "desc",
                List.of(new McpClient.McpPromptArgument("text", "Input text", true)));
        assertEquals(Map.of("text", "hello world"), McpManager.parsePromptArguments(single, "hello world"));

        // Key-value pairs
        McpClient.McpPromptDefinition multi = new McpClient.McpPromptDefinition("test2", "desc",
                List.of(
                        new McpClient.McpPromptArgument("table", "table", true),
                        new McpClient.McpPromptArgument("limit", "limit", false)
                ));
        Map<String, String> kv = McpManager.parsePromptArguments(multi, "table=\"users\" limit=10");
        assertEquals("users", kv.get("table"));
        assertEquals("10", kv.get("limit"));

        // Positional fallback
        Map<String, String> pos = McpManager.parsePromptArguments(multi, "orders 50");
        assertEquals("orders", pos.get("table"));
        assertEquals("50", pos.get("limit"));
    }

    @Test
    @DisplayName("retargetStoredConfigs should update project-bound servers like graphify")
    void testRetargetStoredConfigs() {
        McpManager mgr = McpManager.getInstance();
        java.nio.file.Path targetProject = java.nio.file.Path.of("C:/SampleProject/MyCode");

        McpServerConfig graphifyServer = new McpServerConfig("test_graphify", "uv",
                List.of("run", "--with", "graphifyy", "--with", "mcp", "-m", "graphify.serve", "C:/ForeignApp/graphify-out/graph.json"));
        graphifyServer.setEnabled(false);
        mgr.addServer(graphifyServer);

        mgr.retargetStoredConfigs(targetProject);

        McpServerConfig updated = mgr.getServerConfigs().stream()
                .filter(s -> s.getName().equals("test_graphify"))
                .findFirst().orElse(null);
        assertNotNull(updated);
        String lastArg = updated.getArgs().get(updated.getArgs().size() - 1);
        String expected = targetProject.resolve("graphify-out/graph.json").toAbsolutePath().normalize().toString().replace('\\', '/');
        assertEquals(expected, lastArg.replace('\\', '/'));

        mgr.removeServer("test_graphify");
    }
}


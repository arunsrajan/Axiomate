package com.github.agentforge.agentic.ide.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.agentforge.agentic.ide.agent.AgentManager;
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
}

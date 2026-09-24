package com.github.axiomate.agentic.ide.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.axiomate.agentic.ide.agent.tools.AgentTool;

/**
 * Adapter that exposes a remote MCP tool as a local AgentTool inside the IDE.
 */
public class McpTool implements AgentTool {

    private final String serverName;
    private final String toolName;
    private final String description;
    private final JsonNode inputSchema;
    private final McpClient client;

    public McpTool(String serverName, String toolName, String description, JsonNode inputSchema, McpClient client) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.description = description;
        this.inputSchema = inputSchema;
        this.client = client;
    }

    @Override
    public String getName() {
        return "mcp_" + serverName + "_" + toolName;
    }

    @Override
    public String getDescription() {
        String schemaStr = (inputSchema != null) ? inputSchema.toString() : "{}";
        return "[MCP Server: " + serverName + "] " + toolName + ": " + description + "\nSchema: " + schemaStr;
    }

    @Override
    public String execute(String arguments) throws Exception {
        return client.callTool(toolName, arguments);
    }

    public String getServerName() {
        return serverName;
    }

    public String getRawToolName() {
        return toolName;
    }

    public JsonNode getInputSchema() {
        return inputSchema;
    }
}


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
        return toolName(serverName, toolName);
    }

    /**
     * Model APIs only accept tool names matching [a-zA-Z0-9_-]{1,64}; a server named "my server" or "github.com"
     * otherwise made every request fail. Long names are shortened with a hash to stay unique.
     */
    static String toolName(String server, String tool) {
        String name = ("mcp_" + server + "_" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.length() <= 64) return name;
        String hash = Integer.toHexString(name.hashCode());
        return name.substring(0, 63 - hash.length()) + "_" + hash;
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


package com.github.agentforge.agentic.ide.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration model for an MCP (Model Context Protocol) server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {

    private String name = "";
    private McpTransport transport = McpTransport.STDIO;
    private String command = "";
    private List<String> args = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();
    private String url = "";
    private boolean enabled = true;
    private String description = "";

    public McpServerConfig() {
    }

    public McpServerConfig(String name, String command, List<String> args) {
        this.name = name;
        this.transport = McpTransport.STDIO;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.enabled = true;
    }

    public McpServerConfig(String name, String url) {
        this.name = name;
        this.transport = McpTransport.SSE;
        this.url = url;
        this.enabled = true;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public McpTransport getTransport() {
        return transport;
    }

    public void setTransport(McpTransport transport) {
        this.transport = transport;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env != null ? new HashMap<>(env) : new HashMap<>();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return name + " (" + transport + (enabled ? " - Enabled" : " - Disabled") + ")";
    }
}

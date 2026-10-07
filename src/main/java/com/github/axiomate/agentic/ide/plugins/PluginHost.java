package com.github.axiomate.agentic.ide.plugins;

import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.mcp.McpManager;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;

import java.util.List;

/**
 * The IDE services a plugin contributes to. Abstracted so plugin installation can be tested in isolation.
 */
public interface PluginHost {

    void addMemories(List<MemoryItem> items);

    int removeMemoriesBySource(String source);

    boolean hasMcpServer(String name);

    void addMcpServer(McpServerConfig config);

    void removeMcpServer(String name);

    void setMcpServerEnabled(String name, boolean enabled);

    SlashCommandRegistry commands();

    /**
     * Bridges plugins to the IDE's singleton memory, MCP and command services.
     */
    class Default implements PluginHost {

        @Override
        public void addMemories(List<MemoryItem> items) {
            MemoryManager.getInstance().addMemories(items);
        }

        @Override
        public int removeMemoriesBySource(String source) {
            return MemoryManager.getInstance().removeMemoriesBySource(source);
        }

        @Override
        public boolean hasMcpServer(String name) {
            return McpManager.getInstance().getServerConfigs().stream().anyMatch(s -> s.getName().equalsIgnoreCase(name));
        }

        @Override
        public void addMcpServer(McpServerConfig config) {
            McpManager.getInstance().addServer(config);
        }

        @Override
        public void removeMcpServer(String name) {
            McpManager.getInstance().removeServer(name);
        }

        @Override
        public void setMcpServerEnabled(String name, boolean enabled) {
            McpManager mgr = McpManager.getInstance();
            for (McpServerConfig cfg : mgr.getServerConfigs()) {
                if (cfg.getName().equalsIgnoreCase(name)) {
                    mgr.disconnectServer(cfg.getName());
                    cfg.setEnabled(enabled);
                    mgr.updateServer(cfg);
                    return;
                }
            }
        }

        @Override
        public SlashCommandRegistry commands() {
            return SlashCommandRegistry.getInstance();
        }
    }
}

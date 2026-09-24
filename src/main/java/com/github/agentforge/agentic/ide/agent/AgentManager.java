package com.github.agentforge.agentic.ide.agent;

import com.github.agentforge.agentic.ide.agent.tools.AgentTool;
import com.github.agentforge.agentic.ide.agent.tools.CodeRefactorTool;
import com.github.agentforge.agentic.ide.agent.tools.FileSystemTool;
import com.github.agentforge.agentic.ide.agent.tools.MemoryTool;
import com.github.agentforge.agentic.ide.agent.tools.TerminalTool;
import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton manager coordinating the active AI Agent instance and registered tools.
 */
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);
    private static AgentManager instance;

    private AIAgentService activeService;
    private final MockAgentService mockService;
    private final LangChainAgentService langChainService;

    private AgentManager() {
        this.mockService = new MockAgentService();
        this.langChainService = new LangChainAgentService();

        // Register default tools
        FileSystemTool fsTool = new FileSystemTool();
        TerminalTool termTool = new TerminalTool();
        CodeRefactorTool refactorTool = new CodeRefactorTool();
        MemoryTool memoryTool = new MemoryTool();

        registerTool(fsTool);
        registerTool(termTool);
        registerTool(refactorTool);
        registerTool(memoryTool);

        updateActiveService(ConfigManager.getInstance().getConfig());

        // Listen for config changes
        ConfigManager.getInstance().addListener(this::updateActiveService);
    }

    public static synchronized AgentManager getInstance() {
        if (instance == null) {
            instance = new AgentManager();
        }
        return instance;
    }

    public void registerTool(AgentTool tool) {
        mockService.registerTool(tool);
        langChainService.registerTool(tool);
        log.info("Registered tool: {}", tool.getName());
    }

    private void updateActiveService(IdeConfig config) {
        String provider = config.getAiProvider();
        if ("OPENAI".equalsIgnoreCase(provider) || "CUSTOM".equalsIgnoreCase(provider)) {
            this.activeService = langChainService;
            log.info("Active AI Agent switched to LangChain (Provider: {})", provider);
        } else {
            this.activeService = mockService;
            log.info("Active AI Agent switched to Mock Simulator");
        }
    }

    public AIAgentService getActiveService() {
        return activeService;
    }
}

package com.github.agentforge.agentic.ide.agent;

import com.github.agentforge.agentic.ide.agent.tools.AgentTool;

import java.util.List;

/**
 * Service interface for running AgentForge AI Agent workflows, tool calling, and streaming reasoning.
 */
public interface AIAgentService {

    void sendMessage(String prompt, String contextCode, String activeFilePath, AgentListener listener);

    void cancelCurrentTask();

    boolean isBusy();

    List<AgentTool> getRegisteredTools();

    void registerTool(AgentTool tool);
}

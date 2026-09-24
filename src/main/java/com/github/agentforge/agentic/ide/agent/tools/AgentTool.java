package com.github.agentforge.agentic.ide.agent.tools;

/**
 * Interface representing a tool that can be invoked by the AI Agent.
 */
public interface AgentTool {

    String getName();

    String getDescription();

    String execute(String arguments) throws Exception;
}

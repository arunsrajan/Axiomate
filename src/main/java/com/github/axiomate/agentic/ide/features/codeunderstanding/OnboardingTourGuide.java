package com.github.axiomate.agentic.ide.features.codeunderstanding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 17: "Explain this repo to me" onboarding tour.
 * A guided, interactive walkthrough generated for new team members.
 */
public class OnboardingTourGuide {

    private static final Logger log = LoggerFactory.getLogger(OnboardingTourGuide.class);
    private static OnboardingTourGuide instance;

    private OnboardingTourGuide() {}

    public static synchronized OnboardingTourGuide getInstance() {
        if (instance == null) {
            instance = new OnboardingTourGuide();
        }
        return instance;
    }

    /**
     * Generates a step-by-step repository walkthrough tour.
     */
    public List<TourStop> generateTour(File projectDir) {
        log.info("Generating 'Explain this repo to me' onboarding tour for: {}", projectDir);
        List<TourStop> stops = new ArrayList<>();

        stops.add(new TourStop(
                1,
                "🚀 Welcome & Architecture 10,000 ft View",
                "README.md",
                "High-level Overview",
                "Axiomate is an autonomous Java AI Agent IDE with multi-provider routing (Anthropic, OpenAI, Gemini, Local), 4-tier Agentic Memory, and MCP integration.",
                List.of("Main.java", "IdeConfig.java"),
                "Open Main.java to inspect the application bootstrap lifecycle."
        ));

        stops.add(new TourStop(
                2,
                "🧠 The Autonomous Agent Core & Tool Calling Loop",
                "src/main/java/com/github/axiomate/agentic/ide/agent",
                "AI Agent Subsystem",
                "AIAgentService manages conversation turns, tool specifications, and the multi-turn autonomous tool loop. MockAgentService provides zero-config offline simulation, while LangChainAgentService powers live LLM providers.",
                List.of("AIAgentService", "AgentManager", "LangChainAgentService", "MockAgentService"),
                "Inspect AutonomousTaskRouter to see how prompts route to specialized models."
        ));

        stops.add(new TourStop(
                3,
                "💾 4-Tier Agentic Memory Subsystem",
                "src/main/java/com/github/axiomate/agentic/ide/agent/memory",
                "Memory & Knowledge Invariants",
                "Organized into WORKING, LONG_TERM, EPISODIC, and PROJECT_RULE tiers. It injects relevant rules into LLM prompts and survives across IDE sessions in ~/.axiomate-ide/memory/.",
                List.of("MemoryManager", "AgentMemoryStore", "JsonAgentMemoryStore", "MemoryType"),
                "Press Alt+4 in the IDE or open Terminal -> Memory Tab to browse current memories."
        ));

        stops.add(new TourStop(
                4,
                "🔌 Model Context Protocol (MCP) Client",
                "src/main/java/com/github/axiomate/agentic/ide/mcp",
                "External Tool Ecosystem",
                "Connects to MCP servers over Stdio or SSE transport via JSON-RPC 2.0. Discovers external tools and bridges them dynamically into the agent's tool calling loop.",
                List.of("McpClient", "McpManager", "McpTool"),
                "Open Settings -> MCP Servers (Ctrl+Shift+P) to configure or test tools."
        ));

        stops.add(new TourStop(
                5,
                "💻 Modern Pro Code Editor & Layout",
                "src/main/java/com/github/axiomate/agentic/ide/ui",
                "User Interface & RSyntaxTextArea",
                "MainFrame coordinates the 3-pane split: Project Explorer tree, multi-tab syntax highlighted editor, and interactive AI dock with token meters, sessions, and @ mentions.",
                List.of("MainFrame", "EditorPanel", "AIAgentPanel", "FileMentionController"),
                "Run 'mvn test' to verify the entire test suite passes cleanly."
        ));

        return stops;
    }
}

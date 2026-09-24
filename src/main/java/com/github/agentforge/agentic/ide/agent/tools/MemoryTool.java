package com.github.agentforge.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.agentforge.agentic.ide.agent.memory.AgentMemoryStore;
import com.github.agentforge.agentic.ide.agent.memory.MemoryItem;
import com.github.agentforge.agentic.ide.agent.memory.MemoryManager;
import com.github.agentforge.agentic.ide.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tool allowing the AI Agent to autonomously search, recall, and store memories.
 */
public class MemoryTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "agent_memory";
    }

    @Override
    public String getDescription() {
        return """
            agent_memory: Store, recall, and search agentic memories (rules, past tasks, project facts).
            Arguments JSON schema:
            {
              "action": "search" | "remember" | "recall" | "list",
              "query": "search query or keyword (for search/recall)",
              "title": "short memory title (for remember)",
              "content": "memory content (for remember)",
              "type": "WORKING" | "LONG_TERM" | "EPISODIC" | "PROJECT_RULE" (optional, default LONG_TERM)
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode json = mapper.readTree(arguments);
        String action = json.path("action").asText("search").toLowerCase();
        AgentMemoryStore store = MemoryManager.getInstance().getMemoryStore();

        return switch (action) {
            case "search", "recall" -> {
                String q = json.path("query").asText();
                List<MemoryItem> results = store.search(q, 5);
                if (results.isEmpty()) {
                    yield "No matching memories found for query: " + q;
                }
                yield results.stream()
                        .map(m -> "[" + m.getType() + "] " + m.getTitle() + "\n" + m.getContent())
                        .collect(Collectors.joining("\n---\n"));
            }
            case "remember" -> {
                String title = json.path("title").asText("Fact");
                String content = json.path("content").asText("");
                String typeStr = json.path("type").asText("LONG_TERM");
                MemoryType type;
                try {
                    type = MemoryType.valueOf(typeStr.toUpperCase());
                } catch (Exception e) {
                    type = MemoryType.LONG_TERM;
                }

                MemoryItem item = new MemoryItem(type, title, content, List.of("agent-saved"));
                MemoryManager.getInstance().addMemory(item);
                yield "SUCCESS: Stored new " + type + " memory: '" + title + "'";
            }
            case "list" -> {
                List<MemoryItem> all = store.getAllMemories();
                yield "Total memories: " + all.size() + "\n" +
                        all.stream().map(m -> "- [" + m.getType() + "] " + m.getTitle())
                                .collect(Collectors.joining("\n"));
            }
            default -> throw new IllegalArgumentException("Unknown agent_memory action: " + action);
        };
    }
}

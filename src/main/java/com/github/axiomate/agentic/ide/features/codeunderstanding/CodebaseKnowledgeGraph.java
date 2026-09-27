package com.github.axiomate.agentic.ide.features.codeunderstanding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Feature 15: Codebase knowledge graph.
 * A queryable map of modules, ownership, data flow, and call chains that the agent keeps current.
 */
public class CodebaseKnowledgeGraph {

    private static final Logger log = LoggerFactory.getLogger(CodebaseKnowledgeGraph.class);
    private static CodebaseKnowledgeGraph instance;

    private final Map<String, GraphNode> nodes = new ConcurrentHashMap<>();
    private final List<GraphEdge> edges = new CopyOnWriteArrayList<>();

    private CodebaseKnowledgeGraph() {
        initDefaultAxiomateGraph();
    }

    public static synchronized CodebaseKnowledgeGraph getInstance() {
        if (instance == null) {
            instance = new CodebaseKnowledgeGraph();
        }
        return instance;
    }

    private void initDefaultAxiomateGraph() {
        // Seed graph with Axiomate architecture
        addNode(new GraphNode("ui", "UI Module", "MODULE", "src/main/java/com/github/axiomate/agentic/ide/ui", "Frontend Team", Map.of("layer", "presentation")));
        addNode(new GraphNode("agent", "Agent Engine", "MODULE", "src/main/java/com/github/axiomate/agentic/ide/agent", "AI Core Team", Map.of("layer", "agent-core")));
        addNode(new GraphNode("memory", "Agentic Memory Store", "MODULE", "src/main/java/com/github/axiomate/agentic/ide/agent/memory", "AI Core Team", Map.of("layer", "persistence")));
        addNode(new GraphNode("mcp", "MCP Subsystem", "MODULE", "src/main/java/com/github/axiomate/agentic/ide/mcp", "Integrations Team", Map.of("layer", "integration")));
        addNode(new GraphNode("config", "Config Subsystem", "MODULE", "src/main/java/com/github/axiomate/agentic/ide/config", "Platform Team", Map.of("layer", "infrastructure")));

        addEdge(new GraphEdge("ui", "agent", "CALLS", "UI dispatches user prompts to AIAgentService"));
        addEdge(new GraphEdge("agent", "memory", "DATA_FLOW", "Agent retrieves and updates working/episodic memory"));
        addEdge(new GraphEdge("agent", "mcp", "CALLS", "Agent orchestrates MCP dynamic tools"));
        addEdge(new GraphEdge("agent", "config", "DEPENDS_ON", "Agent reads provider keys and model routing rules"));
        addEdge(new GraphEdge("ui", "config", "DEPENDS_ON", "SettingsDialog updates IdeConfig"));
    }

    public void addNode(GraphNode node) {
        if (node != null) {
            nodes.put(node.id(), node);
        }
    }

    public void addEdge(GraphEdge edge) {
        if (edge != null) {
            edges.add(edge);
        }
    }

    public Optional<GraphNode> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Collection<GraphNode> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public List<GraphEdge> getAllEdges() {
        return Collections.unmodifiableList(edges);
    }

    public List<GraphNode> getDependencies(String nodeId) {
        return edges.stream()
                .filter(e -> e.sourceId().equalsIgnoreCase(nodeId))
                .map(e -> nodes.get(e.targetId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<GraphNode> getDependents(String nodeId) {
        return edges.stream()
                .filter(e -> e.targetId().equalsIgnoreCase(nodeId))
                .map(e -> nodes.get(e.sourceId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public List<GraphEdge> getEdgesFrom(String sourceId) {
        return edges.stream()
                .filter(e -> e.sourceId().equalsIgnoreCase(sourceId))
                .collect(Collectors.toList());
    }

    public List<GraphEdge> getEdgesTo(String targetId) {
        return edges.stream()
                .filter(e -> e.targetId().equalsIgnoreCase(targetId))
                .collect(Collectors.toList());
    }

    public String getModuleOwner(String nodeId) {
        GraphNode node = nodes.get(nodeId);
        return node != null && node.owner() != null ? node.owner() : "Core Team";
    }

    /**
     * Generates a text summary of the graph representation.
     */
    public String generateGraphSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 🕸 Codebase Knowledge Graph (").append(nodes.size()).append(" nodes, ")
                .append(edges.size()).append(" edges)\n\n");
        for (GraphNode node : nodes.values()) {
            sb.append("- **").append(node.name()).append("** (`").append(node.id()).append("` | ").append(node.type())
                    .append(" | Owner: ").append(node.owner()).append(")\n");
            List<GraphNode> deps = getDependencies(node.id());
            if (!deps.isEmpty()) {
                sb.append("  - Dependencies: ");
                sb.append(deps.stream().map(GraphNode::name).collect(Collectors.joining(", ")));
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}

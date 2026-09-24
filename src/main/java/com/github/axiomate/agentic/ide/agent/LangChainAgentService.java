package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.router.AutonomousTaskRouter;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.ContextCompressor;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import com.github.axiomate.agentic.ide.agent.tools.AgentTool;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Autonomous AI Agent service supporting Anthropic, OpenAI, Google Gemini, and Local providers,
 * multi-turn Tool Calling execution loops, multi-agent sessions, and 95% context compression.
 */
public class LangChainAgentService implements AIAgentService {

    private static final Logger log = LoggerFactory.getLogger(LangChainAgentService.class);
    private static final int MAX_TOOL_ITERATIONS = 10;

    private final List<AgentTool> tools = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ObjectMapper mapper = new ObjectMapper();
    private Future<?> activeTask;
    private volatile boolean cancelled = false;

    public LangChainAgentService() {
    }

    @Override
    public List<AgentTool> getRegisteredTools() {
        return new ArrayList<>(tools);
    }

    @Override
    public void registerTool(AgentTool tool) {
        tools.removeIf(t -> t.getName().equalsIgnoreCase(tool.getName()));
        tools.add(tool);
    }

    @Override
    public boolean isBusy() {
        return activeTask != null && !activeTask.isDone();
    }

    @Override
    public void cancelCurrentTask() {
        cancelled = true;
        if (activeTask != null) {
            activeTask.cancel(true);
        }
    }

    @Override
    public void sendMessage(String prompt, String contextCode, String activeFilePath, AgentListener listener) {
        cancelled = false;
        activeTask = executor.submit(() -> {
            String activeProviderName = "Unknown";
            String activeTargetModel = "default";
            String activeEndpointUrl = "default";
            try {
                IdeConfig config = ConfigManager.getInstance().getConfig();
                AgentSession session = SessionManager.getInstance().getActiveSession();

                // 1. Check and trigger 95% Context Compression if needed
                ContextCompressor.CompressionResult preComp = ContextCompressor.compressIfExceeded(
                        session, config.getAutoCompressionThreshold());
                if (preComp.compressed()) {
                    listener.onThinking("⚡ " + preComp.summary());
                }

                // 2. Intelligent Task-Based Model & Provider Routing
                AutonomousTaskRouter.RoutedModel routed = AutonomousTaskRouter.route(
                        prompt, session.getProviderId(), session.getModelId(), session.isAutoRoutingEnabled());

                String providerId = routed.providerId();
                String targetModel = routed.modelId();

                if (!routed.rationale().startsWith("Default")) {
                    listener.onThinking("🎯 " + routed.rationale());
                }

                ProviderConfig providerConfig = config.getProvider(providerId);
                if (providerConfig == null || !providerConfig.isEnabled()) {
                    // Fallback to active session provider or OpenAI
                    providerId = session.getProviderId();
                    providerConfig = config.getProvider(providerId);
                }

                activeProviderName = providerConfig != null ? providerConfig.getName() : providerId;
                activeTargetModel = targetModel;
                activeEndpointUrl = providerConfig != null && providerConfig.getBaseUrl() != null ? providerConfig.getBaseUrl() : "default";

                listener.onThinking(String.format("Connecting to %s [%s] at URL: %s",
                        activeProviderName, activeTargetModel, activeEndpointUrl));

                // 3. Retrieve Agentic Memory context
                String memoryContext = MemoryManager.getInstance().getMemoryStore().getRelevantContext(prompt);

                // 4. Build ChatLanguageModel via Universal Factory
                ChatLanguageModel chatModel = UniversalChatModelFactory.createChatModel(
                        providerConfig, targetModel, config.getTemperature());

                // 5. Prepare Conversation Messages
                List<ChatMessage> messages = new ArrayList<>();
                StringBuilder systemPromptBuilder = new StringBuilder(config.getSystemPrompt());

                if (!session.getSystemPrompt().isBlank()) {
                    systemPromptBuilder.append("\n\nSession Focus:\n").append(session.getSystemPrompt());
                }

                if (!memoryContext.isBlank()) {
                    systemPromptBuilder.append("\n\n").append(memoryContext);
                }

                messages.add(new SystemMessage(systemPromptBuilder.toString()));

                // Replay previous turns from session if applicable
                for (AgentMessage priorMsg : session.getMessages()) {
                    if (priorMsg.isUser()) {
                        messages.add(new UserMessage(priorMsg.getContent()));
                    } else if (priorMsg.isAssistant()) {
                        messages.add(new AiMessage(priorMsg.getContent()));
                    }
                }

                StringBuilder userContent = new StringBuilder();
                if (activeFilePath != null && !activeFilePath.isBlank()) {
                    userContent.append("Active file: ").append(activeFilePath).append("\n");
                }
                if (contextCode != null && !contextCode.isBlank()) {
                    userContent.append("Context Code:\n```\n").append(contextCode).append("\n```\n\n");
                }
                userContent.append("User Request: ").append(prompt);

                messages.add(new UserMessage(userContent.toString()));

                // Add prompt message to active session
                session.addMessage(new AgentMessage(AgentRole.USER, prompt));

                // 6. Convert registered tools (FileSystem, Terminal, CodeRefactor, Memory, and all MCP tools)
                List<ToolSpecification> toolSpecs = buildToolSpecifications();

                // 7. Multi-Turn Autonomous Tool Calling Execution Loop
                int iteration = 0;
                while (iteration++ < MAX_TOOL_ITERATIONS && !cancelled) {
                    listener.onThinking("Reasoning with " + targetModel + " (Step " + iteration + ")...");

                    Response<AiMessage> response = chatModel.generate(messages, toolSpecs);
                    AiMessage aiMessage = response.content();
                    messages.add(aiMessage);

                    // Track tokens from response if provided by provider
                    if (response.tokenUsage() != null) {
                        session.getTokenTracker().recordUsage(
                                response.tokenUsage().inputTokenCount(),
                                response.tokenUsage().outputTokenCount()
                        );
                    } else {
                        // Heuristic fallback
                        session.getTokenTracker().recordUsage(
                                TokenTracker.estimateTokens(prompt),
                                TokenTracker.estimateTokens(aiMessage.text() != null ? aiMessage.text() : "")
                        );
                    }

                    SessionManager.getInstance().notifyListeners();

                    if (aiMessage.hasToolExecutionRequests()) {
                        for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                            if (cancelled) break;

                            String toolName = req.name();
                            String arguments = normalizeArguments(req.arguments());

                            session.addMessage(new AgentMessage(AgentRole.TOOL_CALL, arguments, toolName));
                            listener.onToolCall(toolName, arguments);

                            String toolResult;
                            AgentTool tool = findTool(toolName);
                            if (tool != null) {
                                try {
                                    toolResult = tool.execute(arguments);
                                } catch (Exception ex) {
                                    toolResult = "ERROR executing tool " + toolName + ": " + ex.getMessage();
                                }
                            } else {
                                toolResult = "ERROR: Tool '" + toolName + "' is not registered.";
                            }

                            listener.onToolResult(toolName, toolResult);
                            messages.add(ToolExecutionResultMessage.from(req, toolResult));

                            // Record tool execution in session
                            session.addMessage(new AgentMessage(AgentRole.TOOL, toolResult, toolName));
                        }
                    } else {
                        // Final resolution reached
                        String finalResponse = (aiMessage.text() != null) ? aiMessage.text() : "";

                        // Surface any thinking/reasoning from the model (DeepSeek, Claude 3.7, etc.)
                        // AnthropicMapper stores thinking via LAST_THINKING thread-local after generate()
                        try {
                            String thinkingContent = dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper.LAST_THINKING.get();
                            if (thinkingContent != null && !thinkingContent.isBlank()) {
                                log.debug("Surfacing {} chars of model thinking to UI", thinkingContent.length());
                                session.addMessage(new AgentMessage(AgentRole.THINKING, thinkingContent, null));
                                listener.onThinking("💭 Model Reasoning:\n" + thinkingContent);
                            }
                        } finally {
                            dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper.LAST_THINKING.remove();
                        }

                        // Store in session (actual response only, without thinking)
                        session.addMessage(new AgentMessage(AgentRole.ASSISTANT, finalResponse));
                        MemoryManager.getInstance().recordEpisode(prompt, "Completed via " + providerId + ":" + targetModel);

                        // Post-generation check for 95% limit
                        ContextCompressor.CompressionResult postComp = ContextCompressor.compressIfExceeded(
                                session, config.getAutoCompressionThreshold());
                        if (postComp.compressed()) {
                            log.info("Post-generation context compression: {}", postComp.summary());
                        }

                        // Emit the response as a token so the chat bubble is populated.
                        // chatModel.generate() is synchronous (non-streaming), so onToken() is
                        // the only way to push text into the streaming chat bubble in the UI.
                        if (!finalResponse.isBlank()) {
                            listener.onToken(finalResponse);
                        }

                        SessionManager.getInstance().autoSaveCurrentProjectSessions();
                        listener.onComplete(finalResponse);
                        return;
                    }
                }

                if (iteration >= MAX_TOOL_ITERATIONS) {
                    String msg = "Task reached maximum tool calling iterations (" + MAX_TOOL_ITERATIONS + "). Completed.";
                    session.addMessage(new AgentMessage(AgentRole.ASSISTANT, msg));
                    SessionManager.getInstance().autoSaveCurrentProjectSessions();
                    listener.onToken(msg);
                    listener.onComplete(msg);
                }

            } catch (Exception e) {
                log.error("Failed to execute LangChainAgent task", e);
                listener.onError(new RuntimeException(
                        String.format("Error calling provider %s [%s] at URL [%s]: %s",
                                activeProviderName, activeTargetModel, activeEndpointUrl, e.getMessage()), e));
            }
        });
    }

    private List<ToolSpecification> buildToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (AgentTool tool : tools) {
            Map<String, Map<String, Object>> properties = new HashMap<>();

            Map<String, Object> inputProp = new HashMap<>();
            inputProp.put("type", "string");
            inputProp.put("description", "Arguments conforming to: " + tool.getDescription());
            properties.put("input", inputProp);

            ToolParameters parameters = ToolParameters.builder()
                    .properties(properties)
                    .required(List.of("input"))
                    .build();

            ToolSpecification spec = ToolSpecification.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parameters(parameters)
                    .build();

            specs.add(spec);
        }
        return specs;
    }

    private String normalizeArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = mapper.readTree(rawArguments);
            if (node.has("input")) {
                return node.path("input").asText();
            }
            return rawArguments;
        } catch (Exception e) {
            return rawArguments;
        }
    }

    private AgentTool findTool(String name) {
        for (AgentTool tool : tools) {
            if (tool.getName().equalsIgnoreCase(name)) {
                return tool;
            }
        }
        return null;
    }
}


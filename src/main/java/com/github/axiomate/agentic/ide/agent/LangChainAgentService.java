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
import dev.langchain4j.model.output.FinishReason;
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
    /** A tool call with identical arguments is executed at most this many times per task. */
    static final int MAX_IDENTICAL_TOOL_CALLS = 2;
    /** How many times a reasoning-only (or truncated) step is asked to continue before giving up. */
    static final int MAX_REASONING_CONTINUATIONS = 2;
    private static final int MAX_REASONING_ECHO_CHARS = 8_000;

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

                // Feature 33: Prompt-injection shield validation
                var shieldResult = com.github.axiomate.agentic.ide.features.security.PromptInjectionShield.getInstance()
                        .inspectAndShield("UserPrompt", prompt);
                if (shieldResult.injectionAttemptDetected()) {
                    listener.onThinking("🛡 " + shieldResult.shieldExplanation());
                }

                // Feature 32: Secret-leak guard check
                var leakScan = com.github.axiomate.agentic.ide.features.security.SecretLeakGuard.getInstance()
                        .scanAndSanitize(prompt);
                String safePrompt = leakScan.sanitizedText();
                if (leakScan.leakDetected()) {
                    listener.onThinking("🔒 Blocked credential exposure in prompt (" + leakScan.secretCount() + " secret masked).");
                }

                // Feature 36: Audit trail record
                com.github.axiomate.agentic.ide.features.security.AuditTrailService.getInstance()
                        .recordEvent("USER", "PROMPT", activeFilePath != null ? activeFilePath : "workspace", safePrompt);

                // Feature 2: Living Plan Canvas initialization
                com.github.axiomate.agentic.ide.features.planning.LivingPlanCanvas.getInstance()
                        .generatePlanFromPrompt(safePrompt, activeFilePath);

                // 1. Check and trigger 95% Context Compression if needed
                ContextCompressor.CompressionResult preComp = ContextCompressor.compressIfExceeded(
                        session, config.getAutoCompressionThreshold());
                if (preComp.compressed()) {
                    listener.onThinking("⚡ " + preComp.summary());
                }

                // 2. Intelligent Task-Based Model & Provider Routing
                AutonomousTaskRouter.RoutedModel routed = AutonomousTaskRouter.route(
                        safePrompt, session.getProviderId(), session.getModelId(), session.isAutoRoutingEnabled());

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

                // Host Environment & Shell Execution Policy
                systemPromptBuilder.append("\n\n## Host Environment & Shell Execution Policy\n");
                systemPromptBuilder.append("- Operating System: ").append(com.github.axiomate.agentic.ide.util.OSUtils.getHostEnvironmentSummary()).append("\n");
                if (com.github.axiomate.agentic.ide.util.OSUtils.isWindows()) {
                    systemPromptBuilder.append("- The current host OS is WINDOWS. For command-line execution, running builds/tests, or scripts, ALWAYS invoke the 'powershell' tool (or 'terminal' which defaults to PowerShell).\n");
                    systemPromptBuilder.append("- Do NOT invoke Linux-only commands or assume /bin/bash unless explicitly requested by the user.\n");
                } else {
                    systemPromptBuilder.append("- The current host OS is LINUX / UNIX / MACOS. For command-line execution, running builds/tests, or scripts, ALWAYS invoke the 'bash' tool (or 'terminal' which defaults to Bash).\n");
                    systemPromptBuilder.append("- Do NOT invoke Windows-specific cmdlets or powershell unless explicitly requested by the user.\n");
                }

                messages.add(new SystemMessage(systemPromptBuilder.toString()));

                // Replay previous turns from session if applicable
                for (AgentMessage priorMsg : session.getMessages()) {
                    String content = priorMsg.getContent();
                    if (content == null || content.trim().isEmpty()) {
                        continue;
                    }
                    if (priorMsg.isUser()) {
                        messages.add(new UserMessage(content.trim()));
                    } else if (priorMsg.isAssistant()) {
                        messages.add(new AiMessage(content.trim()));
                    }
                }

                StringBuilder userContent = new StringBuilder();
                if (activeFilePath != null && !activeFilePath.isBlank()) {
                    userContent.append("Active file: ").append(activeFilePath).append("\n");
                }
                if (contextCode != null && !contextCode.isBlank()) {
                    userContent.append("Context Code:\n```\n").append(contextCode).append("\n```\n\n");
                }
                userContent.append("User Request: ").append(safePrompt != null && !safePrompt.isBlank() ? safePrompt : "Process task");

                String userText = userContent.toString().trim();
                if (userText.isEmpty()) {
                    userText = "Process task";
                }
                messages.add(new UserMessage(userText));

                // Add prompt message to active session
                session.addMessage(new AgentMessage(AgentRole.USER, safePrompt));

                // 6. Convert registered tools (FileSystem, Terminal, CodeRefactor, Memory, and all MCP tools)
                List<ToolSpecification> toolSpecs = buildToolSpecifications();

                // 7. Multi-Turn Autonomous Tool Calling Execution Loop
                int maxIterations = config.getMaxAgentIterations();
                ToolCallGuard toolGuard = new ToolCallGuard(MAX_IDENTICAL_TOOL_CALLS);
                boolean finished = false;
                int iteration = 0;
                int continuations = 0;
                while (iteration++ < maxIterations && !cancelled) {
                    listener.onThinking("Reasoning with " + targetModel + " (Step " + iteration + ")...");

                    Response<AiMessage> response = chatModel.generate(messages, toolSpecs);
                    AiMessage aiMessage = response.content();
                    messages.add(aiMessage);

                    // Surface this step's reasoning (thinking models: Claude, DeepSeek...) for every step,
                    // including steps that call tools, and never leak it into the next step.
                    String thinkingContent = takeLastThinking();
                    if (thinkingContent != null) {
                        session.addMessage(new AgentMessage(AgentRole.THINKING, thinkingContent, null));
                        listener.onThinking("💭 Model Reasoning:\n" + thinkingContent);
                    }

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

                    ReasoningOutcome outcome = classifyStep(aiMessage, response.finishReason(), thinkingContent);
                    if (outcome != ReasoningOutcome.COMPLETE && continuations < MAX_REASONING_CONTINUATIONS) {
                        // The model stopped after reasoning (usually the output token limit): keep the turn
                        // structure valid and ask it to carry on instead of ending the task here.
                        continuations++;
                        messages.remove(messages.size() - 1);
                        messages.add(AiMessage.from(thinkingContent != null
                                ? "[My reasoning so far]\n" + truncate(thinkingContent, MAX_REASONING_ECHO_CHARS)
                                : "(My previous response was cut off.)"));
                        messages.add(UserMessage.from(continuationPrompt(outcome)));
                        listener.onThinking("↻ The model returned reasoning without an answer ("
                                + (outcome == ReasoningOutcome.TRUNCATED ? "output token limit reached" : "no final text")
                                + "). Asking it to continue (" + continuations + "/" + MAX_REASONING_CONTINUATIONS + ")...");
                        continue;
                    }

                    if (aiMessage.hasToolExecutionRequests()) {
                        for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                            if (cancelled) break;

                            String toolName = req.name();
                            String arguments = normalizeArguments(req.arguments());

                            session.addMessage(new AgentMessage(AgentRole.TOOL_CALL, arguments, toolName));
                            listener.onToolCall(toolName, arguments);

                            String toolResult;
                            AgentTool tool = findTool(toolName);
                            String repeated = toolGuard.checkRepeat(toolName, arguments);
                            if (repeated != null) {
                                // The model is looping on the same call: don't run it again, point it at the result
                                toolResult = repeated;
                                listener.onThinking("🔁 Skipped a repeated call to " + toolName + " with identical arguments.");
                            } else if (tool != null) {
                                try {
                                    toolResult = tool.execute(arguments);
                                } catch (Exception ex) {
                                    toolResult = "ERROR executing tool " + toolName + ": " + ex.getMessage();
                                }
                            } else {
                                toolResult = "ERROR: Tool '" + toolName + "' is not registered.";
                            }

                            if (toolResult == null || toolResult.trim().isEmpty()) {
                                toolResult = "(command executed with no output)";
                            }
                            if (repeated == null) {
                                toolGuard.record(toolName, arguments, toolResult);
                            }

                            listener.onToolResult(toolName, toolResult);
                            messages.add(ToolExecutionResultMessage.from(req, toolResult));

                            // Record tool execution in session
                            session.addMessage(new AgentMessage(AgentRole.TOOL, toolResult, toolName));
                        }
                    } else {
                        // Final resolution reached - ensure content is never blank
                        String finalResponse = (aiMessage.text() != null && !aiMessage.text().isBlank()) ? aiMessage.text() : "";
                        if (finalResponse.isBlank()) {
                            if (thinkingContent != null) {
                                finalResponse = thinkingContent + "\n\n⚠️ The model only returned reasoning and no final answer. "
                                        + "Try increasing the model's Max Output tokens (Settings → AI Providers → Edit Model).";
                            } else {
                                finalResponse = "Task completed successfully.";
                            }
                        } else if (response.finishReason() == FinishReason.LENGTH) {
                            finalResponse += "\n\n⚠️ Response truncated at the model's output token limit. "
                                    + "Increase Max Output in Settings → AI Providers → Edit Model.";
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

                        // Feature 50: Record analytics outcome
                        com.github.axiomate.agentic.ide.features.devexperience.AgentAnalyticsDashboard.getInstance()
                                .recordTaskOutcome("GENERAL", true, 20.0, 0.005, null);

                        finished = true;
                        listener.onComplete(finalResponse);
                        return;
                    }
                }

                if (!finished && !cancelled) {
                    // Step limit reached: ask the model to wrap up instead of ending with a bare notice
                    listener.onThinking("⏸ Reached the step limit (" + maxIterations + "). Asking the model for a summary...");
                    String summary = null;
                    try {
                        messages.add(UserMessage.from(stepLimitPrompt(maxIterations)));
                        Response<AiMessage> wrapUp = chatModel.generate(messages, toolSpecs);
                        takeLastThinking();
                        if (wrapUp.content().text() != null && !wrapUp.content().text().isBlank()) {
                            summary = wrapUp.content().text();
                        }
                    } catch (Exception wrapUpError) {
                        log.warn("Could not get a summary after the step limit: {}", wrapUpError.getMessage());
                    }
                    String msg = (summary != null ? summary + "\n\n" : "")
                            + "⏸ Paused after " + maxIterations + " agent steps. Reply \"continue\" to keep going, "
                            + "or raise \"Max agent steps per task\" in Settings → Editor & Appearance.";
                    session.addMessage(new AgentMessage(AgentRole.ASSISTANT, msg));
                    SessionManager.getInstance().autoSaveCurrentProjectSessions();
                    listener.onToken(msg);
                    listener.onComplete(msg);
                }

            } catch (Exception e) {
                log.error("Failed to execute LangChainAgent task", e);
                // Record assistant error message in session to avoid leaving an unanswered trailing USER message
                try {
                    AgentSession currentSession = SessionManager.getInstance().getActiveSession();
                    if (currentSession != null) {
                        currentSession.addMessage(new AgentMessage(AgentRole.ASSISTANT, "⚠️ Error: " + e.getMessage()));
                    }
                } catch (Exception ignored) {
                }
                listener.onError(new RuntimeException(
                        String.format("Error calling provider %s [%s] at URL [%s]: %s",
                                activeProviderName, activeTargetModel, activeEndpointUrl, e.getMessage()), e));
            } finally {
                dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper.clearThinkingReplay();
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

    enum ReasoningOutcome {
        /** Tool calls or a final answer: proceed normally. */
        COMPLETE,
        /** Cut off by the output token limit before producing an answer or tool call. */
        TRUNCATED,
        /** Only reasoning, no text and no tool calls. */
        REASONING_ONLY
    }

    /**
     * Decides whether a step ended properly or stopped after reasoning without acting.
     */
    static ReasoningOutcome classifyStep(AiMessage aiMessage, FinishReason finishReason, String thinking) {
        if (aiMessage.hasToolExecutionRequests()) return ReasoningOutcome.COMPLETE;
        boolean hasText = aiMessage.text() != null && !aiMessage.text().isBlank();
        if (hasText) return ReasoningOutcome.COMPLETE;
        if (finishReason == FinishReason.LENGTH) return ReasoningOutcome.TRUNCATED;
        if (thinking != null) return ReasoningOutcome.REASONING_ONLY;
        return ReasoningOutcome.COMPLETE;
    }

    static String continuationPrompt(ReasoningOutcome outcome) {
        return outcome == ReasoningOutcome.TRUNCATED
                ? "Your previous response hit the output token limit before you answered. Continue from your reasoning above "
                  + "without repeating it: keep any further reasoning brief, then either call the next tool or give the final answer."
                : "You reasoned about the task but did not respond. Based on your reasoning above, now either call the next tool "
                  + "or give the final answer.";
    }

    /** Reads and clears the reasoning captured by AnthropicMapper for the last generate() call. */
    private static String takeLastThinking() {
        try {
            String t = dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper.LAST_THINKING.get();
            return t != null && !t.isBlank() ? t : null;
        } finally {
            dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper.LAST_THINKING.remove();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n…";
    }

    static String stepLimitPrompt(int maxIterations) {
        return "You have used all " + maxIterations + " agent steps for this request. Do not call any more tools. "
                + "Reply with: what you completed, what is still left to do, and your best answer so far.";
    }

    /**
     * Detects a model repeatedly issuing the same tool call (a common failure of reasoning models that lose
     * track of earlier results) and answers repeats from the earlier result instead of running them again.
     */
    static final class ToolCallGuard {
        private final int maxIdentical;
        private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        private final java.util.Map<String, String> lastResults = new java.util.HashMap<>();

        ToolCallGuard(int maxIdentical) {
            this.maxIdentical = maxIdentical;
        }

        private static String key(String tool, String args) {
            return tool + "\u0000" + (args == null ? "" : args.replaceAll("\\s+", ""));
        }

        /** Returns a replacement result when this exact call already ran the maximum number of times, else null. */
        String checkRepeat(String tool, String args) {
            String k = key(tool, args);
            int n = counts.getOrDefault(k, 0);
            if (n < maxIdentical) return null;
            counts.put(k, n + 1);
            String previous = lastResults.getOrDefault(k, "");
            if (previous.length() > 4_000) previous = previous.substring(0, 4_000) + "\n…";
            return "NOTE: You already called " + tool + " with these exact arguments " + n + " times; it was not run again. "
                    + "Its result was:\n" + previous + "\n\nUse this result: take a different next step or give the final answer.";
        }

        void record(String tool, String args, String result) {
            String k = key(tool, args);
            counts.merge(k, 1, Integer::sum);
            lastResults.put(k, result);
        }
    }
}

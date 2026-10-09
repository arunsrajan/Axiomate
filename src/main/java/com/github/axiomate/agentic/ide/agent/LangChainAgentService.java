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
import com.github.axiomate.agentic.ide.agent.vision.ImageAttachment;
import com.github.axiomate.agentic.ide.agent.vision.VisionSupport;
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
    /** Images from earlier turns resent with each request (most recent first); older ones become a note. */
    static final int MAX_HISTORY_IMAGES = 6;
    static final int MAX_IDENTICAL_TOOL_CALLS = 2;
    /** How many times a reasoning-only (or truncated) step is asked to continue before giving up. */
    static final int MAX_REASONING_CONTINUATIONS = 2;
    private static final int MAX_REASONING_ECHO_CHARS = 8_000;

    private final List<AgentTool> tools = new CopyOnWriteArrayList<>();
    /** One thread per task, so a task that is still winding down after Stop never delays the next one. */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "axiomate-agent");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Future<?> activeTask;
    /** Control of the latest task. Each task has its own, so starting a new task never revives a stopped one. */
    private volatile TaskControl activeControl = new TaskControl();

    /** Stop flag of one task plus the stream it is reading, which Stop closes (socket reads ignore interrupts). */
    static final class TaskControl {
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile java.io.Closeable openStream;

        boolean get() {
            return cancelled.get();
        }

        void cancel() {
            cancelled.set(true);
            java.io.Closeable c = openStream;
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }

        void streamOpened(java.io.Closeable stream) {
            openStream = stream;
            if (cancelled.get()) cancel();
        }
    }

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
    public boolean recordsSessionMessages() {
        return true;
    }

    @Override
    public boolean isBusy() {
        return activeTask != null && !activeTask.isDone();
    }

    @Override
    public void cancelCurrentTask() {
        activeControl.cancel();
        if (activeTask != null) {
            activeTask.cancel(true);
        }
    }

    @Override
    public void sendMessage(String prompt, String contextCode, String activeFilePath, AgentListener listener) {
        sendMessage(prompt, contextCode, activeFilePath, List.of(), listener);
    }

    @Override
    public void sendMessage(String prompt, String contextCode, String activeFilePath, List<ImageAttachment> images,
                            AgentListener listener) {
        List<ImageAttachment> attached = images != null ? List.copyOf(images) : List.of();
        TaskControl cancel = new TaskControl();
        activeControl = cancel;
        activeTask = executor.submit(() -> {
            AgentSession session = null;
            String activeProviderName = "Unknown";
            String activeTargetModel = "default";
            String activeEndpointUrl = "default";
            try {
                IdeConfig config = ConfigManager.getInstance().getConfig();
                session = SessionManager.getInstance().getActiveSession();
                if (session == null) {
                    throw new IllegalStateException("No active agent session");
                }

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

                // Only worth a note when routing picked something other than the session's own model
                if (!Objects.equals(providerId, session.getProviderId()) || !Objects.equals(targetModel, session.getModelId())) {
                    listener.onThinking("🎯 " + routed.rationale());
                }

                ProviderConfig providerConfig = config.getProvider(providerId);
                if (providerConfig == null || !providerConfig.isEnabled()) {
                    // Fallback to active session provider or OpenAI
                    providerId = session.getProviderId();
                    providerConfig = config.getProvider(providerId);
                }

                // Images need a vision model: if routing picked a text-only one, prefer the session's own model
                boolean vision = VisionSupport.supportsVision(providerConfig, targetModel);
                if (!attached.isEmpty() && !vision && !Objects.equals(targetModel, session.getModelId())) {
                    ProviderConfig sessionProvider = config.getProvider(session.getProviderId());
                    if (sessionProvider != null && sessionProvider.isEnabled()
                            && VisionSupport.supportsVision(sessionProvider, session.getModelId())) {
                        providerId = session.getProviderId();
                        providerConfig = sessionProvider;
                        targetModel = session.getModelId();
                        vision = true;
                        listener.onThinking("🖼 Using " + targetModel + " because the request has images.");
                    }
                }
                VisionSupport.setCurrentModelVision(vision);
                if (!attached.isEmpty() && !vision) {
                    listener.onThinking("🖼 " + targetModel + " does not accept images, so the " + attached.size()
                            + " attached image(s) were not sent. Choose a vision model, or mark this model as "
                            + "vision-capable in Settings → AI Providers → Edit Model.");
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

                // Earlier turns that context compression condensed into a summary
                for (AgentMessage m : session.getMessages()) {
                    if (m.getRole() == AgentRole.SYSTEM && !m.getContent().isBlank()) {
                        systemPromptBuilder.append("\n\n## Earlier in this conversation\n").append(m.getContent().strip());
                    }
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
                List<AgentMessage> history = session.getMessages();
                int imageBudget = MAX_HISTORY_IMAGES;
                Set<AgentMessage> replayImages = Collections.newSetFromMap(new IdentityHashMap<>());
                for (int i = history.size() - 1; i >= 0 && vision && imageBudget > 0; i--) {
                    AgentMessage m = history.get(i);
                    if (m.isUser() && !m.getAttachments().isEmpty()) {
                        replayImages.add(m);
                        imageBudget -= m.getAttachments().size();
                    }
                }
                for (AgentMessage priorMsg : history) {
                    String content = priorMsg.getContent();
                    if ((content == null || content.trim().isEmpty()) && priorMsg.getAttachments().isEmpty()) {
                        continue;
                    }
                    if (priorMsg.isUser()) {
                        messages.add(replayUserMessage(priorMsg, replayImages.contains(priorMsg)));
                    } else if (priorMsg.isAssistant() && !priorMsg.isInterim()) {
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
                if (!attached.isEmpty() && vision) {
                    messages.add(UserMessage.from(VisionSupport.toContents(
                            userText + "\n\nAttached image(s): " + VisionSupport.describe(attached), attached)));
                } else if (!attached.isEmpty()) {
                    messages.add(new UserMessage(userText + "\n\n[" + attached.size()
                            + " image(s) were attached but this model cannot view images: " + VisionSupport.describe(attached) + "]"));
                } else {
                    messages.add(new UserMessage(userText));
                }

                // Add prompt message to active session
                AgentMessage userRecord = new AgentMessage(AgentRole.USER, safePrompt);
                List<String> paths = new ArrayList<>();
                for (ImageAttachment img : attached) {
                    if (img.path() != null) paths.add(img.path());
                }
                userRecord.setAttachments(paths);
                session.addMessage(userRecord);

                // 6. Convert registered tools (FileSystem, Terminal, CodeRefactor, Memory, and all MCP tools)
                List<ToolSpecification> toolSpecs = buildToolSpecifications();

                // 7. Multi-Turn Autonomous Tool Calling Execution Loop
                int maxIterations = config.getMaxAgentIterations();
                ToolCallGuard toolGuard = new ToolCallGuard(MAX_IDENTICAL_TOOL_CALLS);
                boolean finished = false;
                int iteration = 0;
                int continuations = 0;
                while (iteration++ < maxIterations && !cancel.get()) {
                    listener.onThinking("Reasoning with " + targetModel + " (Step " + iteration + ")...");

                    StepStream stream = new StepStream(listener, cancel);
                    Response<AiMessage> response = callModel(chatModel, messages, toolSpecs,
                            config.isStreamingEnabled(), stream);
                    if (cancel.get()) {
                        break; // stopped while the model was answering: report nothing more
                    }
                    AiMessage aiMessage = response.content();
                    messages.add(aiMessage);

                    // Surface this step's reasoning (thinking models: Claude, DeepSeek...) for every step,
                    // including steps that call tools, and never leak it into the next step.
                    String thinkingContent = takeLastThinking();
                    if (thinkingContent != null) {
                        session.addMessage(new AgentMessage(AgentRole.THINKING, thinkingContent, null));
                        if (!stream.reasoningStreamed) {
                            listener.onThinking("💭 Model Reasoning:\n" + thinkingContent);
                        }
                    }

                    // Track tokens from response if provided by provider
                    if (response.tokenUsage() != null && response.tokenUsage().inputTokenCount() != null
                            && response.tokenUsage().inputTokenCount() > 0) {
                        Integer out = response.tokenUsage().outputTokenCount();
                        session.getTokenTracker().recordProviderUsage(response.tokenUsage().inputTokenCount(),
                                out != null ? out : 0);
                    } else {
                        // No usage reported: estimate the whole conversation that was sent, plus the reply
                        session.getTokenTracker().recordProviderUsage(estimateTokens(messages.subList(0, messages.size() - 1)),
                                TokenTracker.estimateTokens(aiMessage.text() != null ? aiMessage.text() : ""));
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
                        // Text written before the tool calls: keep it in the session, and show it when not streamed
                        String narration = aiMessage.text();
                        if (narration != null && !narration.isBlank()) {
                            AgentMessage interim = new AgentMessage(AgentRole.ASSISTANT, narration.strip());
                            interim.setInterim(true);
                            session.addMessage(interim);
                            String unsent = unstreamedPart(narration, stream.text.toString());
                            if (!unsent.isEmpty()) {
                                listener.onToken(unsent);
                            }
                        }
                        for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                            if (cancel.get()) break;

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
                        // Images a tool loaded (view_image) go to the model as a user turn after the tool results
                        List<ImageAttachment> viewed = VisionSupport.drainQueued();
                        if (!viewed.isEmpty() && !cancel.get()) {
                            messages.add(UserMessage.from(VisionSupport.toContents(
                                    "Image(s) loaded by view_image: " + VisionSupport.describe(viewed), viewed)));
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
                        MemoryManager.getInstance().recordEpisode(safePrompt, "Completed via " + providerId + ":" + targetModel);

                        // Post-generation check for 95% limit
                        ContextCompressor.CompressionResult postComp = ContextCompressor.compressIfExceeded(
                                session, config.getAutoCompressionThreshold());
                        if (postComp.compressed()) {
                            log.info("Post-generation context compression: {}", postComp.summary());
                        }

                        // Send whatever the chat has not shown yet: the whole answer for non-streaming models,
                        // only the appended notes (e.g. truncation warning) when the answer was streamed.
                        String unsent = unstreamedPart(finalResponse, stream.text.toString());
                        if (!unsent.isEmpty()) {
                            listener.onToken(unsent);
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

                if (!finished && !cancel.get()) {
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
                if (cancel.get()) {
                    // Stop interrupts the request in flight; that is not an error to report
                    log.info("Agent task stopped by the user");
                    return;
                }
                log.error("Failed to execute LangChainAgent task", e);
                // Record assistant error message in the task's session so it does not end on an unanswered prompt
                try {
                    if (session != null) {
                        session.addMessage(new AgentMessage(AgentRole.ASSISTANT, "⚠️ Error: " + e.getMessage()));
                        SessionManager.getInstance().autoSaveCurrentProjectSessions();
                    }
                } catch (Exception ignored) {
                }
                listener.onError(new RuntimeException(
                        String.format("Error calling provider %s [%s] at URL [%s]: %s",
                                activeProviderName, activeTargetModel, activeEndpointUrl, e.getMessage()), e));
            } finally {
                ReasoningContext.clear();
                VisionSupport.clearThreadState();
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
                JsonNode input = node.get("input");
                // Models sometimes send the tool's JSON as an object instead of a string
                return input.isTextual() ? input.asText() : input.isNull() ? "{}" : input.toString();
            }
            return rawArguments;
        } catch (Exception e) {
            return rawArguments;
        }
    }

    /** Rough token count of messages, for providers that report no usage. */
    static long estimateTokens(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm) total += TokenTracker.estimateTokens(sm.text());
            else if (m instanceof UserMessage um) {
                for (dev.langchain4j.data.message.Content c : um.contents()) {
                    if (c instanceof dev.langchain4j.data.message.TextContent tc) total += TokenTracker.estimateTokens(tc.text());
                }
            } else if (m instanceof AiMessage am) {
                total += TokenTracker.estimateTokens(am.text());
                if (am.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest r : am.toolExecutionRequests()) total += TokenTracker.estimateTokens(r.arguments());
                }
            } else if (m instanceof ToolExecutionResultMessage tr) total += TokenTracker.estimateTokens(tr.text());
        }
        return total;
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
        return ReasoningContext.takeLast();
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

    /** Answer text and reasoning streamed during one model call, forwarded to the listener as it arrives. */
    private static final class StepStream implements StreamingChat.Sink {
        private final AgentListener listener;
        private final TaskControl cancel;
        final StringBuilder text = new StringBuilder();
        boolean reasoningStreamed;

        StepStream(AgentListener listener, TaskControl cancel) {
            this.listener = listener;
            this.cancel = cancel;
        }

        @Override
        public void onText(String delta) {
            if (delta == null || delta.isEmpty() || cancel.get()) return;
            text.append(delta);
            listener.onToken(delta);
        }

        @Override
        public void onReasoning(String delta) {
            if (delta == null || delta.isEmpty() || cancel.get()) return;
            reasoningStreamed = true;
            listener.onReasoningToken(delta);
        }

        @Override
        public boolean isCancelled() {
            return cancel.get();
        }

        @Override
        public void onStreamOpened(java.io.Closeable stream) {
            cancel.streamOpened(stream);
        }
    }

    /** Streams when the model supports it and streaming is enabled; otherwise a normal blocking call. */
    static Response<AiMessage> callModel(ChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools,
                                         boolean streaming, StreamingChat.Sink sink) {
        if (streaming && model instanceof StreamingChat streamingModel) {
            return streamingModel.generateStreaming(messages, tools, sink);
        }
        return model.generate(messages, tools);
    }

    /**
     * The part of the final answer the chat has not received yet. When the stream does not match the final text
     * exactly, nothing more is sent and the listener's onComplete carries the authoritative text.
     */
    static String unstreamedPart(String finalResponse, String streamed) {
        if (streamed.isEmpty()) return finalResponse;
        if (finalResponse.startsWith(streamed)) return finalResponse.substring(streamed.length());
        return "";
    }

    /** Rebuilds an earlier user turn, with its images when they are still on disk and within the budget. */
    static UserMessage replayUserMessage(AgentMessage msg, boolean withImages) {
        String text = msg.getContent() == null ? "" : msg.getContent().trim();
        List<String> files = msg.getAttachments();
        if (files.isEmpty()) return new UserMessage(text.isEmpty() ? "(empty)" : text);
        List<ImageAttachment> images = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String path : files) {
            java.io.File f = new java.io.File(path);
            names.add(f.getName());
            if (withImages && f.isFile()) {
                try {
                    images.add(VisionSupport.fromFile(f));
                } catch (Exception e) {
                    log.debug("Could not reload image {}: {}", path, e.getMessage());
                }
            }
        }
        if (images.isEmpty()) {
            return new UserMessage((text.isEmpty() ? "" : text + "\n\n") + "[Earlier image(s): " + String.join(", ", names) + "]");
        }
        return UserMessage.from(VisionSupport.toContents(text, images));
    }

}

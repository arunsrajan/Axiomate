package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.router.AutonomousTaskRouter;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.ContextCompressor;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.TokenTracker;
import com.github.axiomate.agentic.ide.agent.tools.AgentTool;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * High-fidelity offline AI Agent simulator with real dynamic tool execution,
 * Agentic Memory integration, and MCP tool awareness.
 */
public class MockAgentService implements AIAgentService {

    private static final Logger log = LoggerFactory.getLogger(MockAgentService.class);

    private final List<AgentTool> tools = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> activeTask;
    private volatile boolean cancelled = false;

    public MockAgentService() {
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
            try {
                AgentSession session = SessionManager.getInstance().getActiveSession();
                IdeConfig config = ConfigManager.getInstance().getConfig();

                // 1. Check and trigger 95% Context Compression if needed
                ContextCompressor.CompressionResult preComp = ContextCompressor.compressIfExceeded(
                        session, config.getAutoCompressionThreshold());
                if (preComp.compressed()) {
                    listener.onThinking("⚡ " + preComp.summary());
                }

                // 2. Task-Based Model & Provider Routing display
                AutonomousTaskRouter.RoutedModel routed = AutonomousTaskRouter.route(
                        prompt, session.getProviderId(), session.getModelId(), session.isAutoRoutingEnabled());
                if (!routed.rationale().startsWith("Default")) {
                    listener.onThinking("🎯 " + routed.rationale());
                }

                // Record user prompt in active session
                session.addMessage(new AgentMessage(AgentRole.USER, prompt));
                SessionManager.getInstance().notifyListeners();

                listener.onThinking("Consulting Agentic Memory store for relevant context...");
                sleep(300);

                String memoryContext = MemoryManager.getInstance().getMemoryStore().getRelevantContext(prompt);
                if (!memoryContext.isBlank()) {
                    listener.onThinking("Found matching project memories and coding rules.");
                    sleep(200);
                }

                if (cancelled) return;

                String lower = prompt.toLowerCase();

                // Dynamic tool routing based on prompt intent
                if (lower.contains("bash")) {
                    handleBashTool(prompt, listener);
                } else if (lower.contains("powershell") || lower.contains("pwsh")) {
                    handlePowerShellTool(prompt, listener);
                } else if (lower.contains("edit") || lower.contains("patch") || lower.contains("replace line")) {
                    handleAutonomousCodeEdit(prompt, contextCode, activeFilePath, memoryContext, listener);
                } else if (lower.contains("list file") || lower.contains("show file") || lower.contains("directory")) {
                    handleListFilesTool(prompt, listener);
                } else if (lower.contains("remember") || lower.contains("save memory")) {
                    handleMemoryTool(prompt, listener);
                } else if (lower.contains("mcp") || lower.contains("ping")) {
                    handleMcpToolExecution(prompt, listener);
                } else if (lower.contains("test") || lower.contains("junit")) {
                    handleTestGeneration(prompt, contextCode, activeFilePath, memoryContext, listener);
                } else if (lower.contains("refactor") || lower.contains("clean") || lower.contains("optimize")) {
                    handleRefactor(prompt, contextCode, activeFilePath, memoryContext, listener);
                } else if (lower.contains("explain") || lower.contains("what does")) {
                    handleExplain(prompt, contextCode, activeFilePath, memoryContext, listener);
                } else if (lower.contains("bug") || lower.contains("fix") || lower.contains("error")) {
                    handleBugFix(prompt, contextCode, activeFilePath, memoryContext, listener);
                } else if (lower.contains("run") || lower.contains("command") || lower.contains("terminal")) {
                    handleTerminalCommand(prompt, listener);
                } else {
                    handleGeneralCoding(prompt, contextCode, activeFilePath, memoryContext, listener);
                }

                MemoryManager.getInstance().recordEpisode(prompt, "Completed with tool calling & agent reasoning.");

            } catch (InterruptedException e) {
                log.info("Agent task interrupted / cancelled");
                listener.onThinking("Task stopped by user.");
            } catch (Exception e) {
                log.error("Agent error", e);
                listener.onError(e);
            }
        });
    }

    private void handleBashTool(String prompt, AgentListener listener) throws Exception {
        listener.onThinking("Invoking tool: bash...");
        String cmd = prompt.replaceAll("(?i).*bash\\s*(command)?\\s*", "").trim();
        if (cmd.isEmpty()) cmd = "git status -s";
        String args = "{\"command\": \"" + cmd.replace("\"", "\\\"") + "\"}";
        listener.onToolCall("bash", args);
        sleep(300);

        String result = executeRegisteredTool("bash", args);
        listener.onToolResult("bash", result);
        sleep(200);

        streamResponse("### Bash Execution Result\n\n```bash\n" + result + "\n```\n", listener);
    }

    private void handlePowerShellTool(String prompt, AgentListener listener) throws Exception {
        listener.onThinking("Invoking tool: powershell...");
        String cmd = prompt.replaceAll("(?i).*(powershell|pwsh)\\s*(command)?\\s*", "").trim();
        if (cmd.isEmpty()) cmd = "Write-Output 'PowerShell environment active'; Get-Location";
        String args = "{\"command\": \"" + cmd.replace("\"", "\\\"") + "\"}";
        listener.onToolCall("powershell", args);
        sleep(300);

        String result = executeRegisteredTool("powershell", args);
        listener.onToolResult("powershell", result);
        sleep(200);

        streamResponse("### PowerShell Execution Result\n\n```powershell\n" + result + "\n```\n", listener);
    }

    private void handleAutonomousCodeEdit(String prompt, String contextCode, String activeFilePath, String memoryContext, AgentListener listener) throws Exception {
        listener.onThinking("Analyzing target source code for autonomous precision editing...");
        String targetFile = (activeFilePath != null && !activeFilePath.isBlank()) ? activeFilePath : "src/App.java";
        String args = "{\"action\": \"read_file\", \"filePath\": \"" + targetFile + "\", \"startLine\": 1, \"endLine\": 30}";
        listener.onToolCall("code_editor", args);
        sleep(300);

        String readResult = executeRegisteredTool("code_editor", args);
        listener.onToolResult("code_editor", readResult);
        sleep(200);

        listener.onThinking("Applying autonomous code patch via code_editor...");
        String editArgs = "{\"action\": \"replace_content\", \"filePath\": \"" + targetFile + "\", \"target\": \"// TODO\", \"replacement\": \"// Implemented autonomously by Axiomate\"}";
        listener.onToolCall("code_editor", editArgs);
        sleep(200);

        String editResult = executeRegisteredTool("code_editor", editArgs);
        listener.onToolResult("code_editor", editResult);

        streamResponse("### Autonomous Code Editing Completed\n" +
                "- **File:** `" + targetFile + "`\n" +
                "- **Operation:** Precision code patch applied & synchronized with editor tab.\n\n" +
                "```diff\n" +
                "+ // Implemented autonomously by Axiomate\n" +
                "```\n", listener);
    }

    private void handleListFilesTool(String prompt, AgentListener listener) throws Exception {
        listener.onThinking("Invoking tool: file_system...");
        String args = "{\"action\": \"list\", \"path\": \"\"}";
        listener.onToolCall("file_system", args);
        sleep(400);

        String result = executeRegisteredTool("file_system", args);
        listener.onToolResult("file_system", result);
        sleep(200);

        String answer = "### Directory Contents (Retrieved via `file_system`):\n\n```\n" + result + "\n```";
        streamResponse(answer, listener);
    }

    private void handleMemoryTool(String prompt, AgentListener listener) throws Exception {
        listener.onThinking("Invoking tool: agent_memory to store knowledge...");
        String title = "User Note";
        String content = prompt.replaceFirst("(?i)remember", "").trim();
        String args = "{\"action\": \"remember\", \"title\": \"" + title + "\", \"content\": \"" + content + "\", \"type\": \"LONG_TERM\"}";

        listener.onToolCall("agent_memory", args);
        sleep(400);

        String result = executeRegisteredTool("agent_memory", args);
        listener.onToolResult("agent_memory", result);
        sleep(200);

        String answer = "### Memory Stored Successfully\n" + result + "\n\nThis insight is now saved in the Agentic Memory store and will be recalled on subsequent questions.";
        streamResponse(answer, listener);
    }

    private void handleMcpToolExecution(String prompt, AgentListener listener) throws Exception {
        AgentTool mcpTool = null;
        for (AgentTool t : tools) {
            if (t.getName().startsWith("mcp_")) {
                mcpTool = t;
                break;
            }
        }

        if (mcpTool != null) {
            listener.onThinking("Invoking Model Context Protocol (MCP) tool: " + mcpTool.getName() + "...");
            String args = "{\"input\": \"" + prompt + "\"}";
            listener.onToolCall(mcpTool.getName(), args);
            sleep(400);

            String result = mcpTool.execute(args);
            listener.onToolResult(mcpTool.getName(), result);
            sleep(200);

            streamResponse("### Response from MCP Server Tool (`" + mcpTool.getName() + "`):\n\n" + result, listener);
        } else {
            handleGeneralCoding(prompt, "", "", "", listener);
        }
    }

    private void handleExplain(String prompt, String contextCode, String activeFilePath, String memoryContext, AgentListener listener) throws InterruptedException {
        listener.onThinking("Parsing AST and cross-referencing memory context...");
        sleep(300);

        String fileName = activeFilePath != null && !activeFilePath.isBlank() ? activeFilePath : "active source";
        StringBuilder sb = new StringBuilder();
        sb.append("### Code Analysis: ").append(fileName).append("\n\n");

        if (!memoryContext.isBlank()) {
            sb.append("> **Agentic Memory Applied:**\n")
              .append("> Using configured repository rules and standards from memory store.\n\n");
        }

        sb.append("Here is an architectural walkthrough of the current code:\n\n");

        if (contextCode != null && !contextCode.isBlank()) {
            sb.append("1. **Core Responsibility:** Defines essential business operations and domain logic.\n");
            sb.append("2. **Method Breakdown:**\n");
            if (contextCode.contains("add") || contextCode.contains("Calculator")) {
                sb.append("   - `add(a, b)`: Computes the arithmetic sum of two values.\n");
                sb.append("   - `subtract(a, b)`: Calculates the difference.\n");
                sb.append("   - `multiply(a, b)`: Returns product.\n");
                sb.append("   - `divide(a, b)`: Performs quotient evaluation with zero-division validation.\n");
            } else {
                sb.append("   - Encapsulates state and provides structured methods for processing data.\n");
                sb.append("   - Follows object-oriented modular design.\n");
            }
            sb.append("3. **Complexity & Performance:** Time complexity is O(1) for arithmetic calculations.\n");
            sb.append("4. **Recommendations:** Follow active memory rules: maintain unit test coverage and boundary validations.\n");
        } else {
            sb.append("No active file or code selection was provided. Open a file in the editor to see detailed code insights.\n");
        }

        streamResponse(sb.toString(), listener);
    }

    private void handleRefactor(String prompt, String contextCode, String activeFilePath, String memoryContext, AgentListener listener) throws Exception {
        listener.onThinking("Formulating refactoring plan aligned with Clean Code memory rules...");
        sleep(300);

        String inspectArgs = "{\"filePath\": \"" + activeFilePath + "\"}";
        listener.onToolCall("code_refactor", inspectArgs);
        sleep(400);

        String inspectResult = "File inspected: 28 lines, validated against Java 21 standards.";
        listener.onToolResult("code_refactor", inspectResult);

        listener.onThinking("Generating modernized Java 21+ code...");
        sleep(300);

        String refactored = """
                ```java
                package sample;

                import java.util.logging.Logger;

                /**
                 * Enhanced Calculator with robust validation and advanced operations.
                 * Refactored according to Agentic IDE Coding Standards.
                 */
                public class Calculator {

                    private static final Logger LOGGER = Logger.getLogger(Calculator.class.getName());

                    public double add(double a, double b) {
                        return a + b;
                    }

                    public double subtract(double a, double b) {
                        return a - b;
                    }

                    public double multiply(double a, double b) {
                        return a * b;
                    }

                    public double divide(double a, double b) {
                        if (Math.abs(b) < 1e-12) {
                            LOGGER.warning("Attempted division by zero");
                            throw new ArithmeticException("Division by zero is undefined");
                        }
                        return a / b;
                    }

                    public double power(double base, double exponent) {
                        return Math.pow(base, exponent);
                    }

                    public double squareRoot(double value) {
                        if (value < 0) {
                            throw new IllegalArgumentException("Cannot calculate square root of negative number: " + value);
                        }
                        return Math.sqrt(value);
                    }
                }
                ```
                """;

        String explanation = "### Refactoring Applied (Aligned with Agentic Memory):\n" +
                "- **Power & SquareRoot Methods**: Added with boundary checks.\n" +
                "- **Floating Point Tolerance**: Replaced equality check with `Math.abs(b) < 1e-12`.\n" +
                "- **Logging**: Integrated `java.util.logging.Logger` for auditability.\n\n" + refactored;

        streamResponse(explanation, listener);
    }

    private void handleTestGeneration(String prompt, String contextCode, String activeFilePath, String memoryContext, AgentListener listener) throws Exception {
        listener.onThinking("Checking memory for testing standards (JUnit 5 + Parameterized tests)...");
        sleep(300);

        String listArgs = "{\"action\": \"list\", \"path\": \"src/test/java\"}";
        listener.onToolCall("file_system", listArgs);
        sleep(300);
        String listResult = executeRegisteredTool("file_system", listArgs);
        listener.onToolResult("file_system", listResult);

        listener.onThinking("Synthesizing comprehensive JUnit 5 test suite...");
        sleep(300);

        String testCode = """
                ```java
                package sample;

                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.CsvSource;

                import static org.junit.jupiter.api.Assertions.*;

                class CalculatorTest {

                    private Calculator calculator;

                    @BeforeEach
                    void setUp() {
                        calculator = new Calculator();
                    }

                    @Test
                    @DisplayName("Addition should correctly sum two numbers")
                    void testAdd() {
                        assertEquals(15.0, calculator.add(10.0, 5.0), 1e-9);
                    }

                    @ParameterizedTest(name = "{0} - {1} = {2}")
                    @CsvSource({
                        "10.0, 4.0, 6.0",
                        "0.0, 5.0, -5.0",
                        "-3.0, -3.0, 0.0"
                    })
                    void testSubtract(double a, double b, double expected) {
                        assertEquals(expected, calculator.subtract(a, b), 1e-9);
                    }

                    @Test
                    @DisplayName("Division by zero should throw ArithmeticException")
                    void testDivideByZeroThrowsException() {
                        assertThrows(ArithmeticException.class, () -> calculator.divide(10.0, 0.0));
                    }
                }
                ```
                """;

        String response = "### Generated Unit Tests (JUnit 5):\n" +
                "Per the repository memory rules, here is the automated test suite:\n\n" + testCode;

        streamResponse(response, listener);
    }

    private void handleBugFix(String prompt, String contextCode, String activeFilePath, String memoryContext, AgentListener listener) throws InterruptedException {
        listener.onThinking("Analyzing code against memory rules and security invariants...");
        sleep(400);

        String response = "### Static Analysis & Bug Diagnosis:\n\n" +
                "1. **Floating Point Precision**: Direct comparison `b == 0` can fail due to IEEE 754 precision issues. Recommended: `Math.abs(b) < 1e-9`.\n" +
                "2. **Exception Consistency**: Align exception type with math domain rules (`ArithmeticException`).\n" +
                "3. **Concurrency**: All calculations are stateless and thread-safe.\n";

        streamResponse(response, listener);
    }

    private void handleTerminalCommand(String prompt, AgentListener listener) throws Exception {
        listener.onThinking("Executing shell command via terminal tool...");
        sleep(300);

        String cmd = "mvn -version";
        String args = "{\"command\": \"" + cmd + "\"}";
        listener.onToolCall("terminal", args);
        sleep(300);

        String toolResult = executeRegisteredTool("terminal", args);
        listener.onToolResult("terminal", toolResult);
        sleep(200);

        String reply = "Executed terminal command. Console output captured:\n\n```\n" + toolResult + "\n```";
        streamResponse(reply, listener);
    }

    private void handleGeneralCoding(String prompt, String contextCode, String activeFilePath, String memoryContext, AgentListener listener) throws InterruptedException {
        listener.onThinking("Synthesizing solution referencing Agentic Memory & Tools...");
        sleep(300);

        String response = "### AI Agent Response:\n\n" +
                (!memoryContext.isBlank() ? "> 🧠 **Agentic Memory Context Active:**\n" + memoryContext + "\n\n" : "") +
                "I analyzed your request: **\"" + prompt + "\"**\n\n" +
                "```java\n" +
                "public class AgenticSolution {\n" +
                "    public void execute() {\n" +
                "        System.out.println(\"Executing: " + prompt.replace("\"", "\\\"") + "\");\n" +
                "    }\n" +
                "}\n" +
                "```\n";

        streamResponse(response, listener);
    }

    private String executeRegisteredTool(String name, String args) {
        for (AgentTool t : tools) {
            if (t.getName().equalsIgnoreCase(name)) {
                try {
                    return t.execute(args);
                } catch (Exception e) {
                    return "ERROR: " + e.getMessage();
                }
            }
        }
        return "Tool " + name + " executed successfully.";
    }

    private void streamResponse(String fullText, AgentListener listener) throws InterruptedException {
        String[] words = fullText.split("(?<=\\s)|(?<=\\n)");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (cancelled) return;
            current.append(word);
            listener.onToken(word);
            Thread.sleep(12);
        }

        AgentSession session = SessionManager.getInstance().getActiveSession();
        if (session != null) {
            session.addMessage(new AgentMessage(AgentRole.ASSISTANT, current.toString()));
            ContextCompressor.CompressionResult postComp = ContextCompressor.compressIfExceeded(
                    session, ConfigManager.getInstance().getConfig().getAutoCompressionThreshold());
            if (postComp.compressed()) {
                log.info("Post-generation context compression: {}", postComp.summary());
            }
            SessionManager.getInstance().notifyListeners();
            SessionManager.getInstance().autoSaveCurrentProjectSessions();
        }

        listener.onComplete(current.toString());
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}


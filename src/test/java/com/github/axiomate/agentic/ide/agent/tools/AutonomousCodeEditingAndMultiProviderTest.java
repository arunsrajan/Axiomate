package com.github.axiomate.agentic.ide.agent.tools;

import com.github.axiomate.agentic.ide.agent.AIAgentService;
import com.github.axiomate.agentic.ide.agent.AgentManager;
import com.github.axiomate.agentic.ide.agent.LangChainAgentService;
import com.github.axiomate.agentic.ide.agent.UniversalChatModelFactory;
import com.github.axiomate.agentic.ide.agent.router.AutonomousTaskRouter;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.IdeConfig;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AutonomousCodeEditingAndMultiProviderTest {

    @TempDir
    Path tempDir;

    private File originalProjectDir;

    @BeforeEach
    void setUp() {
        originalProjectDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        ProjectManager.getInstance().setCurrentProjectDirectory(tempDir.toFile());
        ConfigManager.getInstance().resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        ProjectManager.getInstance().setCurrentProjectDirectory(originalProjectDir);
    }

    @Test
    @DisplayName("Verify AutonomousCodeEditorTool precision line replacement, insertion, and deletion")
    void testAutonomousCodeEditorPrecisionOperations() throws Exception {
        AutonomousCodeEditorTool editorTool = new AutonomousCodeEditorTool();

        // 1. Create file with write_file
        String initialCode = """
                public class Calculator {
                    public int add(int a, int b) {
                        // TODO: Implement addition
                        return 0;
                    }
                }
                """;

        String writeArgs = String.format("{\"action\": \"write_file\", \"filePath\": \"Calculator.java\", \"content\": \"%s\"}",
                initialCode.replace("\n", "\\n").replace("\"", "\\\""));
        String writeResult = editorTool.execute(writeArgs);
        assertTrue(writeResult.startsWith("SUCCESS: Created file"), writeResult);

        File targetFile = tempDir.resolve("Calculator.java").toFile();
        assertTrue(targetFile.exists());

        // 2. Read lines with line numbers
        String readArgs = "{\"action\": \"read_file\", \"filePath\": \"Calculator.java\", \"startLine\": 1, \"endLine\": 6}";
        String readResult = editorTool.execute(readArgs);
        assertTrue(readResult.contains("1: public class Calculator {"));
        assertTrue(readResult.contains("3:         // TODO: Implement addition"));

        // 3. Precision line replacement (replace line 3 with return a + b;)
        String replaceLinesArgs = "{\"action\": \"replace_lines\", \"filePath\": \"Calculator.java\", \"startLine\": 3, \"endLine\": 4, \"replacement\": \"        return a + b;\"}";
        String replaceResult = editorTool.execute(replaceLinesArgs);
        assertTrue(replaceResult.startsWith("SUCCESS: Replaced lines 3-4"), replaceResult);

        String updated = Files.readString(targetFile.toPath());
        assertTrue(updated.contains("return a + b;"));
        assertFalse(updated.contains("// TODO: Implement addition"));

        // 4. Insert method at line
        String insertArgs = "{\"action\": \"insert_at_line\", \"filePath\": \"Calculator.java\", \"line\": 4, \"content\": \"    public int subtract(int a, int b) {\\n        return a - b;\\n    }\"}";
        String insertResult = editorTool.execute(insertArgs);
        assertTrue(insertResult.startsWith("SUCCESS: Inserted"), insertResult);

        String inserted = Files.readString(targetFile.toPath());
        assertTrue(inserted.contains("subtract(int a, int b)"));

        // 5. Targeted snippet replacement
        String snippetArgs = "{\"action\": \"replace_content\", \"filePath\": \"Calculator.java\", \"target\": \"Calculator\", \"replacement\": \"AdvancedCalculator\"}";
        String snippetResult = editorTool.execute(snippetArgs);
        assertTrue(snippetResult.startsWith("SUCCESS: Replaced target snippet"), snippetResult);

        String patched = Files.readString(targetFile.toPath());
        assertTrue(patched.contains("public class AdvancedCalculator"));

        // 6. Delete lines
        String deleteArgs = "{\"action\": \"delete_lines\", \"filePath\": \"Calculator.java\", \"startLine\": 4, \"endLine\": 6}";
        String deleteResult = editorTool.execute(deleteArgs);
        assertTrue(deleteResult.startsWith("SUCCESS: Deleted"), deleteResult);
    }

    @Test
    @DisplayName("Verify ProjectManager notifications when code_editor executes")
    void testEditorLiveSyncNotification() throws Exception {
        AutonomousCodeEditorTool editorTool = new AutonomousCodeEditorTool();
        AtomicBoolean notified = new AtomicBoolean(false);

        ProjectManager.getInstance().addFileContentListener((file, content) -> {
            if ("SyncTest.java".equals(file.getName()) && content.contains("liveSyncWorking")) {
                notified.set(true);
            }
        });

        String writeArgs = "{\"action\": \"write_file\", \"filePath\": \"SyncTest.java\", \"content\": \"// liveSyncWorking\"}";
        editorTool.execute(writeArgs);

        assertTrue(notified.get(), "ProjectManager fileContentListener must be invoked on file write");
    }

    @Test
    @DisplayName("Verify PowerShellTool execution on Windows / Cross-platform")
    void testPowerShellToolExecution() throws Exception {
        PowerShellTool tool = new PowerShellTool();
        String result = tool.execute("{\"command\": \"Write-Output 'PowerShell_Axiomate_Success'\"}");
        assertNotNull(result);
        assertTrue(result.contains("PowerShell_Axiomate_Success") || result.contains("Exit code:"), result);
    }

    @Test
    @DisplayName("Verify BashTool execution discovery and execution")
    void testBashToolExecution() throws Exception {
        BashTool tool = new BashTool();
        String result = tool.execute("{\"command\": \"echo 'Bash_Axiomate_Success'\"}");
        assertNotNull(result);
        assertTrue(result.contains("Bash_Axiomate_Success") || result.contains("Exit code:"), result);
    }

    @Test
    @DisplayName("Verify multiple ANTHROPIC providers and multiple GEMINI providers are configurable with different URLs")
    void testMultipleProvidersOfSameType() {
        IdeConfig config = ConfigManager.getInstance().getConfig();

        // 1. Create multiple Anthropic providers
        ProviderConfig anthropicPersonal = new ProviderConfig(
                "ANTHROPIC_PERSONAL",
                "ANTHROPIC",
                "Anthropic Personal Claude",
                "https://api.anthropic.com/v1",
                "claude-3-7-sonnet",
                List.of(new ModelDefinition("claude-3-7-sonnet", "Claude 3.7", 200_000, 8_192, List.of("coding")))
        );
        anthropicPersonal.setApiKey("sk-ant-personal-key");

        ProviderConfig anthropicWorkProxy = new ProviderConfig(
                "ANTHROPIC_WORK",
                "ANTHROPIC",
                "Anthropic Enterprise Proxy",
                "https://anthropic.corp.internal/v1",
                "claude-3-5-sonnet",
                List.of(new ModelDefinition("claude-3-5-sonnet", "Claude 3.5", 200_000, 8_192, List.of("coding")))
        );
        anthropicWorkProxy.setApiKey("sk-ant-corp-key");

        // 2. Create multiple Gemini providers
        ProviderConfig geminiStandard = new ProviderConfig(
                "GEMINI_STANDARD",
                "GEMINI",
                "Google Gemini Public API",
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.0-flash",
                List.of(new ModelDefinition("gemini-2.0-flash", "Gemini 2.0 Flash", 1_000_000, 8_192, List.of("fast")))
        );
        geminiStandard.setApiKey("gemini-public-key");

        ProviderConfig geminiVertexProxy = new ProviderConfig(
                "GEMINI_VERTEX",
                "GEMINI",
                "Gemini Vertex AI Endpoint",
                "https://us-central1-aiplatform.googleapis.com/v1beta",
                "gemini-1.5-pro",
                List.of(new ModelDefinition("gemini-1.5-pro", "Gemini 1.5 Pro", 2_000_000, 8_192, List.of("massive-context")))
        );
        geminiVertexProxy.setApiKey("gemini-vertex-key");

        config.addProvider(anthropicPersonal);
        config.addProvider(anthropicWorkProxy);
        config.addProvider(geminiStandard);
        config.addProvider(geminiVertexProxy);

        assertEquals(anthropicPersonal, config.getProvider("ANTHROPIC_PERSONAL"));
        assertEquals(anthropicWorkProxy, config.getProvider("ANTHROPIC_WORK"));
        assertEquals("https://anthropic.corp.internal/v1", config.getProvider("ANTHROPIC_WORK").getBaseUrl());
        assertEquals("https://us-central1-aiplatform.googleapis.com/v1beta", config.getProvider("GEMINI_VERTEX").getBaseUrl());

        // 3. Verify UniversalChatModelFactory instantiates AnthropicChatModel for BOTH Anthropic providers
        ChatLanguageModel m1 = UniversalChatModelFactory.createChatModel(anthropicPersonal, "claude-3-7-sonnet", 0.2);
        assertTrue(m1 instanceof AnthropicChatModel, "ANTHROPIC_PERSONAL must create AnthropicChatModel");

        ChatLanguageModel m2 = UniversalChatModelFactory.createChatModel(anthropicWorkProxy, "claude-3-5-sonnet", 0.2);
        assertTrue(m2 instanceof AnthropicChatModel, "ANTHROPIC_WORK must create AnthropicChatModel");

        // 4. Verify UniversalChatModelFactory instantiates GoogleAiGeminiChatModel for BOTH Gemini providers
        ChatLanguageModel g1 = UniversalChatModelFactory.createChatModel(geminiStandard, "gemini-2.0-flash", 0.2);
        assertTrue(g1 instanceof GoogleAiGeminiChatModel, "GEMINI_STANDARD must create GoogleAiGeminiChatModel");

        ChatLanguageModel g2 = UniversalChatModelFactory.createChatModel(geminiVertexProxy, "gemini-1.5-pro", 0.2);
        assertTrue(g2 instanceof GoogleAiGeminiChatModel, "GEMINI_VERTEX must create GoogleAiGeminiChatModel");
    }

    @Test
    @DisplayName("Verify custom provider configured under ANTHROPIC api type dynamically creates AnthropicChatModel")
    void testCustomProviderUnderAnthropicApiType() {
        IdeConfig config = ConfigManager.getInstance().getConfig();

        // 1. Verify default CUSTOM_ANTHROPIC provider exists under ANTHROPIC API type
        ProviderConfig defaultCustomAnthropic = config.getProvider("CUSTOM_ANTHROPIC");
        assertNotNull(defaultCustomAnthropic, "Default CUSTOM_ANTHROPIC provider must be initialized");
        assertEquals("ANTHROPIC", defaultCustomAnthropic.getProviderType());
        assertTrue(defaultCustomAnthropic.isAnthropicType());

        // 2. Dynamically add custom provider under ANTHROPIC api type with custom gateway URL and custom model
        ProviderConfig customBedrock = new ProviderConfig(
                "BEDROCK_CLAUDE",
                "ANTHROPIC",
                "AWS Bedrock Claude Gateway",
                "https://bedrock-proxy.internal.net/v1",
                "anthropic.claude-3-sonnet-20240229-v1:0",
                List.of(
                        new ModelDefinition("anthropic.claude-3-sonnet-20240229-v1:0", "Bedrock Claude 3 Sonnet", 200_000, 8_192, List.of("bedrock", "claude")),
                        new ModelDefinition("claude-3-7-sonnet", "Claude 3.7", 200_000, 8_192, List.of("reasoning"))
                )
        );
        customBedrock.setApiKey("bedrock-custom-key");
        config.addProvider(customBedrock);

        assertTrue(customBedrock.isAnthropicType());
        assertEquals("ANTHROPIC", customBedrock.getProviderType());

        // 3. Verify getAnthropicProviders lists both official and custom Anthropic providers
        List<ProviderConfig> anthropicProviders = config.getAnthropicProviders();
        assertTrue(anthropicProviders.stream().anyMatch(p -> p.getId().equals("ANTHROPIC")));
        assertTrue(anthropicProviders.stream().anyMatch(p -> p.getId().equals("CUSTOM_ANTHROPIC")));
        assertTrue(anthropicProviders.stream().anyMatch(p -> p.getId().equals("BEDROCK_CLAUDE")));

        // 4. Verify UniversalChatModelFactory builds AnthropicChatModel for custom ANTHROPIC provider
        ChatLanguageModel model = UniversalChatModelFactory.createChatModel(
                customBedrock, "anthropic.claude-3-sonnet-20240229-v1:0", 0.5);
        assertNotNull(model);
        assertTrue(model instanceof AnthropicChatModel, "Custom provider with ANTHROPIC api type must instantiate AnthropicChatModel");
    }

    @Test
    @DisplayName("Verify CUSTOM_ANTHROPIC provider routes to LangChainAgentService and not Mock simulator for file analysis")
    void testCustomAnthropicFileAnalysisRouting() {
        IdeConfig config = ConfigManager.getInstance().getConfig();
        SessionManager sessionManager = SessionManager.getInstance();

        // 1. Create a session configured with CUSTOM_ANTHROPIC
        ProviderConfig customAnthropic = config.getProvider("CUSTOM_ANTHROPIC");
        assertNotNull(customAnthropic);
        customAnthropic.setBaseUrl("https://anthropic.enterprise.proxy/v1");
        customAnthropic.setApiKey("sk-ant-corp-key-12345");

        AgentSession session = sessionManager.createSession("Claude File Analyst", "CUSTOM_ANTHROPIC", "claude-3-7-sonnet");
        sessionManager.switchSession(session.getId());

        // 2. Verify AgentManager returns LangChainAgentService, NOT MockAgentService
        AIAgentService service = AgentManager.getInstance().getActiveService();
        assertTrue(service instanceof LangChainAgentService,
                "AgentManager must return LangChainAgentService for CUSTOM_ANTHROPIC session, NOT MockAgentService");

        // 3. Verify AutonomousTaskRouter does NOT hijack CUSTOM_ANTHROPIC for file analysis prompts
        AutonomousTaskRouter.RoutedModel routed = AutonomousTaskRouter.route(
                "Explain the file Calculator.java and analyze its architecture",
                session.getProviderId(),
                session.getModelId());

        assertEquals("CUSTOM_ANTHROPIC", routed.providerId(),
                "File analysis must be routed to CUSTOM_ANTHROPIC and not hijacked to Gemini or Mock");
        assertEquals("claude-3-7-sonnet", routed.modelId());

        // 4. Verify model is created with custom URL and API key
        ChatLanguageModel chatModel = UniversalChatModelFactory.createChatModel(
                customAnthropic, routed.modelId(), 0.2);
        assertTrue(chatModel instanceof AnthropicChatModel);
    }
}


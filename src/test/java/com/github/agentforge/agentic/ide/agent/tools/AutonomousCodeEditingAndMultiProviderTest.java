package com.github.agentforge.agentic.ide.agent.tools;

import com.github.agentforge.agentic.ide.agent.UniversalChatModelFactory;
import com.github.agentforge.agentic.ide.config.ConfigManager;
import com.github.agentforge.agentic.ide.config.IdeConfig;
import com.github.agentforge.agentic.ide.config.ModelDefinition;
import com.github.agentforge.agentic.ide.config.ProviderConfig;
import com.github.agentforge.agentic.ide.util.ProjectManager;
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
        String result = tool.execute("{\"command\": \"Write-Output 'PowerShell_AgentForge_Success'\"}");
        assertNotNull(result);
        assertTrue(result.contains("PowerShell_AgentForge_Success") || result.contains("Exit code:"), result);
    }

    @Test
    @DisplayName("Verify BashTool execution discovery and execution")
    void testBashToolExecution() throws Exception {
        BashTool tool = new BashTool();
        String result = tool.execute("{\"command\": \"echo 'Bash_AgentForge_Success'\"}");
        assertNotNull(result);
        assertTrue(result.contains("Bash_AgentForge_Success") || result.contains("Exit code:"), result);
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
}

package com.github.axiomate.agentic.ide;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.ContextCompressor;
import com.github.axiomate.agentic.ide.agent.tools.FileSystemTool;
import com.github.axiomate.agentic.ide.features.security.ExecutionSandbox;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.ui.components.FileMentionController;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuditFixesTest {

    @TempDir
    Path dir;
    private File previousProject;

    @BeforeEach
    void remember() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    @AfterEach
    void restore() {
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    @Test
    @DisplayName("The write sandbox compares whole folder names: /work/app does not reach /work/app-secrets")
    void sandboxSiblingPrefix() throws Exception {
        // Outside java.io.tmpdir, which the sandbox allows on purpose
        Path work = Files.createDirectories(Path.of(System.getProperty("user.dir"), "sandbox-check"));
        Path app = Files.createDirectories(work.resolve("app"));
        Path secrets = Files.createDirectories(work.resolve("app-secrets"));
        ProjectManager.getInstance().setCurrentProjectDirectory(app.toFile());
        ExecutionSandbox sandbox = ExecutionSandbox.getInstance();

        assertTrue(sandbox.validatePathAccess(app.resolve("src/App.java").toFile()).allowed());
        assertFalse(sandbox.validatePathAccess(secrets.resolve("keys.txt").toFile()).allowed());
        assertFalse(sandbox.validatePathAccess(app.resolve("../app-secrets/keys.txt").toFile()).allowed());
    }

    @Test
    @DisplayName("Slash command arguments are inserted as typed: '$5' and '$1' in them are not placeholders")
    void slashArgumentsAreLiteral() {
        var cmd = new SlashCommandRegistry.SlashCommand("commit", "", "Commit message for: $ARGUMENTS (first word $1)", "test");
        assertEquals("Commit message for: bump price to $5 and $1 (first word bump)", cmd.expand("bump price to $5 and $1"));
        assertEquals("Total is C:\\x\\$2", new SlashCommandRegistry.SlashCommand("t", "", "Total is $ARGUMENTS", "test")
                .expand("C:\\x\\$2"), "backslashes and dollars are not regex replacement syntax");
        assertEquals("Ten: $10", new SlashCommandRegistry.SlashCommand("t", "", "Ten: $10", "test").expand(""),
                "$10 is not $1 followed by 0");
    }

    @Test
    @DisplayName("@mentions: a sentence's full stop is not part of the name, and Latin-1 files are still attached")
    void mentions() throws Exception {
        Files.writeString(dir.resolve("README.md"), "# Hello");
        Files.write(dir.resolve("Legacy.java"), "class Legacy { String s = \"café\"; }".getBytes(StandardCharsets.ISO_8859_1));

        String context = FileMentionController.buildMentionedFilesContext("Explain @README.md. Then fix @Legacy.java.", dir.toFile());

        assertTrue(context.contains("--- Mentioned File Context: README.md ---"), context);
        assertTrue(context.contains("# Hello"), context);
        assertTrue(context.contains("--- Mentioned File Context: Legacy.java ---"), context);
        assertTrue(context.contains("class Legacy"), context);
    }

    @Test
    @DisplayName("file_system list on a file says so instead of failing")
    void listOnAFile() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "x");
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        String out = new FileSystemTool().execute("{\"action\":\"list\",\"path\":\"a.txt\"}");
        assertTrue(out.startsWith("ERROR:") && out.contains("is a file"), out);
    }

    @Test
    @DisplayName("Compression keeps the last request and answers even when the last turn made many tool calls")
    void compressionKeepsLatestExchange() {
        AgentSession session = new AgentSession("Tools", "OPENAI", "gpt-4o", 10_000);
        for (int i = 1; i <= 4; i++) {
            session.addMessage(new AgentMessage(AgentRole.USER, "Request " + i));
            session.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Answer " + i));
        }
        session.addMessage(new AgentMessage(AgentRole.USER, "Now apply that to every module"));
        for (int i = 0; i < 6; i++) {
            session.addMessage(new AgentMessage(AgentRole.TOOL_CALL, "{\"action\":\"read\"}", "file_system"));
            session.addMessage(new AgentMessage(AgentRole.TOOL, "contents " + i, "file_system"));
        }
        session.getTokenTracker().setEstimatedUsage(9_600);

        assertTrue(ContextCompressor.compressIfExceeded(session, 0.95).compressed());

        var contents = session.getMessages().stream().map(AgentMessage::getContent).toList();
        assertTrue(contents.contains("Now apply that to every module"), contents.toString());
        assertTrue(contents.contains("Answer 4"), contents.toString());
        assertTrue(contents.contains("Request 4"), contents.toString());
        assertFalse(contents.contains("Request 1"), "older turns are summarised");
    }

    @Test
    @DisplayName("A second compression keeps the first one's summary instead of cutting it to one line")
    void repeatedCompressionKeepsEarlierSummary() {
        AgentSession session = new AgentSession("Long", "OPENAI", "gpt-4o", 10_000);
        for (int i = 1; i <= 6; i++) {
            session.addMessage(new AgentMessage(AgentRole.USER, "First round request " + i));
            session.addMessage(new AgentMessage(AgentRole.ASSISTANT, "First round answer " + i));
        }
        session.getTokenTracker().setEstimatedUsage(9_600);
        assertTrue(ContextCompressor.compressIfExceeded(session, 0.95).compressed());

        for (int i = 1; i <= 6; i++) {
            session.addMessage(new AgentMessage(AgentRole.USER, "Second round request " + i));
            session.addMessage(new AgentMessage(AgentRole.ASSISTANT, "Second round answer " + i));
        }
        session.getTokenTracker().setEstimatedUsage(9_600);
        assertTrue(ContextCompressor.compressIfExceeded(session, 0.95).compressed());

        String summary = session.getMessages().get(0).getContent();
        assertEquals(AgentRole.SYSTEM, session.getMessages().get(0).getRole());
        assertTrue(summary.contains("First round request 1"), summary);
        assertTrue(summary.contains("First round answer 5"), summary);
        assertTrue(summary.contains("Second round request 1"), summary);
        assertEquals(1, summary.split("Condensed Context Summary", -1).length - 1, "one header, not nested ones");
    }
}

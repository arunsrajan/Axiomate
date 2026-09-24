package com.github.axiomate.agentic.ide.config;

import com.github.axiomate.agentic.ide.ui.components.FileMentionController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectStateAndFileMentionTest {

    @Test
    @DisplayName("Configuration directory should be ~/.axiomate-ide and migrate from legacy directories")
    void testAppDirectoryName() {
        assertEquals(".axiomate-ide", ConfigManager.APP_DIR_NAME);
        assertEquals(".agentforge-ide", ConfigManager.LEGACY_APP_DIR_NAME);
        assertEquals(".agentic-ide", ConfigManager.ORIGINAL_LEGACY_APP_DIR_NAME);
        Path appDir = ConfigManager.getAppDirectory();
        assertTrue(appDir.toString().replace('\\', '/').endsWith(".axiomate-ide"));
        assertFalse(appDir.toString().replace('\\', '/').endsWith(".agentforge-ide"));
        assertFalse(appDir.toString().replace('\\', '/').endsWith(".agentic-ide"));
    }

    @Test
    @DisplayName("ProjectStateManager should save, persist, and load project state")
    void testProjectStateSaveAndLoad(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("project_state.json");
        ProjectStateManager stateManager = new ProjectStateManager(stateFile);

        Path projectDir = tempDir.resolve("sample-repo");
        Files.createDirectories(projectDir);

        Path f1 = projectDir.resolve("App.java");
        Path f2 = projectDir.resolve("pom.xml");
        Files.writeString(f1, "public class App {}");
        Files.writeString(f2, "<project></project>");

        List<File> openFiles = List.of(f1.toFile(), f2.toFile());
        File activeFile = f1.toFile();

        stateManager.saveProjectState(projectDir.toFile(), openFiles, activeFile);

        assertTrue(Files.exists(stateFile), "State file should exist on disk");

        // Reload fresh from disk
        ProjectStateManager reloaded = new ProjectStateManager(stateFile);
        ProjectState loadedState = reloaded.getProjectState(projectDir.toFile());

        assertNotNull(loadedState, "Loaded project state should not be null");
        assertEquals("sample-repo", loadedState.getProjectName());
        assertEquals(2, loadedState.getOpenFiles().size());

        String normF1 = ProjectStateManager.normalizePath(f1.toFile());
        String normF2 = ProjectStateManager.normalizePath(f2.toFile());
        assertEquals(normF1, loadedState.getActiveFile());
        assertTrue(loadedState.getOpenFiles().contains(normF1));
        assertTrue(loadedState.getOpenFiles().contains(normF2));
        assertTrue(loadedState.getLastOpenedTime() > 0);

        String normProj = ProjectStateManager.normalizePath(projectDir.toFile());
        assertEquals(normProj, reloaded.getLastOpenProjectPath());
    }

    @Test
    @DisplayName("ProjectStateManager should handle closing a project")
    void testProjectStateClose(@TempDir Path tempDir) throws IOException {
        Path stateFile = tempDir.resolve("project_state.json");
        ProjectStateManager stateManager = new ProjectStateManager(stateFile);

        Path projectDir = tempDir.resolve("closed-repo");
        Files.createDirectories(projectDir);

        Path f1 = projectDir.resolve("Main.java");
        Files.writeString(f1, "public class Main {}");

        stateManager.saveProjectState(projectDir.toFile(), List.of(f1.toFile()), f1.toFile());
        assertEquals(ProjectStateManager.normalizePath(projectDir.toFile()), stateManager.getLastOpenProjectPath());

        // Close project
        stateManager.closeProject(projectDir.toFile(), List.of(f1.toFile()), f1.toFile());

        ProjectState state = stateManager.getProjectState(projectDir.toFile());
        assertNotNull(state);
        assertTrue(state.getLastClosedTime() > 0);
        assertEquals("", stateManager.getLastOpenProjectPath(), "Active project path should be cleared on close");
    }

    @Test
    @DisplayName("FileMentionController should correctly extract query at caret")
    void testFileMentionQueryExtraction() {
        // Simple @ at beginning
        String text1 = "@";
        FileMentionController.MentionQuery q1 = FileMentionController.extractQueryAtCaret(text1, 1, "@");
        assertNotNull(q1);
        assertEquals("", q1.query);
        assertEquals(0, q1.startOffset);

        // Preceded by whitespace
        String text2 = "Please review @pom";
        FileMentionController.MentionQuery q2 = FileMentionController.extractQueryAtCaret(text2, text2.length(), "@");
        assertNotNull(q2);
        assertEquals("pom", q2.query);
        assertEquals(14, q2.startOffset);

        // Substring inside email should NOT trigger mention
        String text3 = "Contact dev@example.com";
        FileMentionController.MentionQuery q3 = FileMentionController.extractQueryAtCaret(text3, 12, "@");
        assertNull(q3, "Email address @ without leading whitespace should not trigger mention");

        // Closed mention with trailing space
        String text4 = "@pom.xml ";
        FileMentionController.MentionQuery q4 = FileMentionController.extractQueryAtCaret(text4, text4.length(), "@");
        assertNull(q4, "Completed mention with trailing space should not trigger popup");
    }

    @Test
    @DisplayName("FileMentionController should build context from @ mentioned files in prompt")
    void testMentionContextInjection(@TempDir Path tempWorkspace) throws IOException {
        Path f1 = tempWorkspace.resolve("Service.java");
        Path subDir = tempWorkspace.resolve("src");
        Files.createDirectories(subDir);
        Path f2 = subDir.resolve("Helper.java");

        Files.writeString(f1, "public class Service { void run() {} }");
        Files.writeString(f2, "public class Helper { int calculate() { return 42; } }");

        String prompt = "Can you optimize @Service.java and also look at @src/Helper.java?";
        String context = FileMentionController.buildMentionedFilesContext(prompt, tempWorkspace.toFile());

        assertNotNull(context);
        assertTrue(context.contains("Mentioned File Context: Service.java"));
        assertTrue(context.contains("public class Service"));
        assertTrue(context.contains("Mentioned File Context: src/Helper.java"));
        assertTrue(context.contains("public class Helper"));
    }

    @Test
    @DisplayName("FileMentionController should return empty context if no mentions or files do not exist")
    void testMentionContextEmptyWhenNoFiles(@TempDir Path tempWorkspace) {
        String promptWithoutMentions = "Explain how dependency injection works in Spring.";
        String context1 = FileMentionController.buildMentionedFilesContext(promptWithoutMentions, tempWorkspace.toFile());
        assertEquals("", context1);

        String promptWithNonexistentFile = "Check @NonExistentFile.java please";
        String context2 = FileMentionController.buildMentionedFilesContext(promptWithNonexistentFile, tempWorkspace.toFile());
        assertEquals("", context2);
    }

    @Test
    @DisplayName("IdeConfig file mention settings should be configurable")
    void testIdeConfigFileMentionSettings() {
        IdeConfig config = new IdeConfig();
        assertTrue(config.isFileMentionsEnabled());
        assertEquals("@", config.getMentionTriggerChar());

        config.setFileMentionsEnabled(false);
        assertFalse(config.isFileMentionsEnabled());

        config.setMentionTriggerChar("#");
        assertEquals("#", config.getMentionTriggerChar());
    }
}


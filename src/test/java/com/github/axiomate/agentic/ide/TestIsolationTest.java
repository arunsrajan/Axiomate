package com.github.axiomate.agentic.ide;

import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Surefire setup in pom.xml: tests must not touch the developer's real ~/.axiomate-ide or the repository's
 * own .axiomate folder.
 */
class TestIsolationTest {

    private static Path buildDir() {
        // the test JVM runs in target/test-work
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    }

    @Test
    @DisplayName("Tests use a throwaway home and working folder under target/")
    void homeAndWorkingFolderAreUnderTarget() {
        Path build = buildDir();
        assertTrue(build.endsWith("target"), "working folder should be target/test-work but is " + System.getProperty("user.dir"));
        assertTrue(Path.of(System.getProperty("user.home")).toAbsolutePath().startsWith(build),
                "user.home should be under target/ but is " + System.getProperty("user.home"));
    }

    @Test
    @DisplayName("Saved config and project state go to the throwaway home")
    void stateFilesAreUnderTarget() {
        Path build = buildDir();
        Path state = ProjectStateManager.getInstance().getStateFilePath().toAbsolutePath();
        assertTrue(state.startsWith(build), "project state file " + state);
        Path appDir = Path.of(System.getProperty("user.home"), ConfigManager.APP_DIR_NAME).toAbsolutePath();
        assertTrue(appDir.startsWith(build), "app folder " + appDir);
    }
}

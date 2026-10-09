package com.github.axiomate.agentic.ide.config;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.agent.memory.JsonAgentMemoryStore;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryManager;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceFixesTest {

    @TempDir
    Path dir;
    private File previousProject;

    @BeforeEach
    void remember() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    @AfterEach
    void restore() {
        File back = previousProject != null ? previousProject : new File(System.getProperty("user.dir"));
        ProjectManager.getInstance().setCurrentProjectDirectory(back);
        SessionManager.getInstance().loadSessionsForProject(back);
    }

    @Test
    @DisplayName("An unreadable workspace state file is kept, not overwritten with an empty one")
    void unreadableStateIsBackedUp() throws Exception {
        Path state = dir.resolve("project_state.json");
        Files.writeString(state, "{\"projects\": {\"/p\": {\"sessions\": [ {\"id\": \"abc\"");  // cut off mid-write
        new ProjectStateManager(state);
        List<Path> backups;
        try (var s = Files.list(dir)) {
            backups = s.filter(p -> p.getFileName().toString().startsWith("project_state.json.unreadable-")).toList();
        }
        assertEquals(1, backups.size(), "a copy of the unreadable file is kept");
        assertTrue(Files.readString(backups.get(0)).contains("\"id\": \"abc\""));
        assertTrue(Files.readString(state).contains("projects"), "the fresh state is valid JSON");
        try (var s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temporary files left behind");
        }
    }

    @Test
    @DisplayName("Unreadable memories are kept; search ignores short words and stopwords")
    void memoryStore() throws Exception {
        Path file = dir.resolve("agent_memory.json");
        Files.writeString(file, "[{\"id\":\"1\",\"title\":\"My hard-won rule\"");
        JsonAgentMemoryStore store = new JsonAgentMemoryStore(file);
        try (var s = Files.list(dir)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("agent_memory.json.unreadable-")));
        }

        store.clear();
        store.addMemory(new MemoryItem(MemoryType.LONG_TERM, "Gradle builds", "Use the wrapper ./gradlew for builds.", List.of("build")));
        store.addMemory(new MemoryItem(MemoryType.LONG_TERM, "Logging", "Use SLF4J and never System.out.", List.of("logging")));
        assertTrue(store.search("what is a to do", 5).isEmpty(), "stopwords and short words match nothing");
        List<String> hits = store.search("how do I run the gradle build?", 5).stream().map(MemoryItem::getTitle).toList();
        assertEquals(List.of("Gradle builds"), hits);
    }

    @Test
    @DisplayName("Task episodes are capped")
    void episodesAreCapped() {
        MemoryManager mm = MemoryManager.getInstance();
        for (int i = 0; i < 205; i++) mm.recordEpisode("task " + i, "done");
        long episodes = mm.getMemoryStore().getAllMemories().stream()
                .filter(m -> m.getTags().contains("task-history")).count();
        assertTrue(episodes <= 200, "episodes kept: " + episodes);
    }

    @Test
    @DisplayName("The config file holding API keys is readable by its owner only")
    void configIsOwnerOnly() throws Exception {
        ConfigManager.getInstance().saveConfig(ConfigManager.getInstance().getConfig());
        Path config = ConfigManager.getAppDirectory().resolve(ConfigManager.CONFIG_FILE_NAME);
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(config);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        } catch (UnsupportedOperationException windows) {
            // no POSIX permissions on this file system
        }
    }

    @Test
    @DisplayName("Importing the same sessions twice replaces them; imported and moved sessions belong to this project")
    void sessionImportAndProjectPaths() throws Exception {
        File project = Files.createDirectories(dir.resolve("app")).toFile();
        ProjectManager.getInstance().setCurrentProjectDirectory(project);
        SessionManager sm = SessionManager.getInstance();
        sm.loadSessionsForProject(project);

        Path export = dir.resolve("export.json");
        Files.writeString(export, "{\"activeSessionId\": null, \"sessions\": [{\"id\": \"s-1\", \"name\": \"Imported\","
                + " \"projectPath\": \"/somewhere/else\", \"messages\": []}]}");
        sm.importSessionsFromFile(export.toFile(), true);
        sm.importSessionsFromFile(export.toFile(), true);
        List<AgentSession> matches = sm.getSessions().stream().filter(s -> s.getId().equals("s-1")).collect(Collectors.toList());
        assertEquals(1, matches.size(), "no duplicate ids");
        assertEquals(project.getAbsolutePath(), matches.get(0).getProjectPath());

        // A project folder that was moved: its saved sessions still point at the old place
        AgentSession moved = sm.createSession("Moved", "MOCK", "mock-agent");
        moved.setProjectPath("/old/location/app");
        moved.addMessage(new AgentMessage(AgentRole.USER, "hi"));
        sm.autoSaveCurrentProjectSessions();
        sm.loadSessionsForProject(project);
        assertEquals(project.getAbsolutePath(), sm.findSession(moved.getId()).getProjectPath());
    }

    @Test
    @DisplayName("An unlisted model does not inherit another model's limits")
    void exactModelLookup() {
        ProviderConfig p = new ProviderConfig("P", "ANTHROPIC", "P", null, "claude-opus-4-1",
                new ArrayList<>(List.of(new ModelDefinition("claude-opus-4-1", "Opus", 200_000, 32_000, List.of()))));
        assertNull(p.findExactModel("claude-haiku-4-5"));
        assertNotNull(p.findModel("claude-haiku-4-5"), "findModel keeps its fallback for other callers");
        assertEquals(200_000, SessionManagerHelper.contextFor(p, "claude-opus-4-1"));
    }

    /** Package-private access to the factory helper. */
    static final class SessionManagerHelper {
        static int contextFor(ProviderConfig p, String model) {
            ModelDefinition m = p.findExactModel(model);
            return m != null ? m.getMaxContextTokens() : 128_000;
        }
    }
}

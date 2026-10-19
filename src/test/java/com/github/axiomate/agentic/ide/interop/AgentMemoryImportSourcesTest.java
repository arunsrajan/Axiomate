package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.agent.memory.JsonAgentMemoryStore;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.DetectedSource;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ImportOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Memory import from Claude Code, OpenAI Codex and Gemini CLI: {@code @path} imports, agent home
 * directory overrides, and settings-driven instruction file names.
 */
class AgentMemoryImportSourcesTest {

    @TempDir
    Path tmp;
    Path home;
    Path project;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        project = Files.createDirectories(tmp.resolve("work/my-app"));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private JsonAgentMemoryStore emptyStore() {
        JsonAgentMemoryStore store = new JsonAgentMemoryStore(tmp.resolve("store.json"));
        store.clear();
        return store;
    }

    private static Optional<DetectedSource> source(List<DetectedSource> sources, Path file) {
        Path key = file.toAbsolutePath().normalize();
        return sources.stream().filter(s -> s.file().equals(key)).findFirst();
    }

    private String projectKey() {
        return ProjectStateManager.normalizePath(project.toFile());
    }

    // ------------------------------------------------------------------
    // @path imports
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@imports in CLAUDE.md are followed recursively; code spans, fences and annotations are ignored")
    void claudeImportsAreFollowed() throws IOException {
        write(project.resolve("CLAUDE.md"), """
                # Project
                See @docs/style.md for conventions.
                @AGENTS.md
                Use `@docs/not-imported.md` literally, and annotate with @Override.
                ```
                @docs/fenced.md
                ```
                Personal prefs: @~/shared/prefs.md.
                """);
        write(project.resolve("docs/style.md"), "## Naming\nUse camelCase.\n## Imports\n@./nested/deep.md\n");
        write(project.resolve("docs/nested/deep.md"), "Deep rule.\n@../style.md\n"); // cycle back to style.md
        write(project.resolve("docs/not-imported.md"), "should not be imported");
        write(project.resolve("docs/fenced.md"), "should not be imported");
        write(project.resolve("AGENTS.md"), "## Commands\nmvn test\n");
        write(home.resolve("shared/prefs.md"), "Prefer short answers.");

        AgentMemoryInterop interop = new AgentMemoryInterop(AgentEnvironment.isolated(home));
        List<DetectedSource> sources = interop.detectAll(project);

        DetectedSource style = source(sources, project.resolve("docs/style.md")).orElseThrow();
        assertEquals(CodingAgent.CLAUDE_CODE, style.agent());
        assertEquals(MemoryScope.PROJECT, style.location().scope());
        assertTrue(style.location().description().contains("Imported by CLAUDE.md"));

        DetectedSource deep = source(sources, project.resolve("docs/nested/deep.md")).orElseThrow();
        assertTrue(deep.location().description().contains("Imported by style.md"), "nested imports are followed");
        assertTrue(source(sources, home.resolve("shared/prefs.md")).isPresent(), "@~/ resolves against home");

        // AGENTS.md is a native Codex location: it keeps that attribution and is listed once
        assertEquals(CodingAgent.CODEX, source(sources, project.resolve("AGENTS.md")).orElseThrow().agent());
        assertEquals(1, sources.stream().filter(s -> s.file().getFileName().toString().equals("AGENTS.md")).count());

        assertTrue(source(sources, project.resolve("docs/not-imported.md")).isEmpty(), "inline code is not an import");
        assertTrue(source(sources, project.resolve("docs/fenced.md")).isEmpty(), "fenced code is not an import");
        assertEquals(1, sources.stream().filter(s -> s.file().endsWith("style.md")).count(), "cycles terminate");

        JsonAgentMemoryStore store = emptyStore();
        interop.importInto(store, sources, project, ImportOptions.defaults());
        List<MemoryItem> all = store.getAllMemories();
        MemoryItem naming = all.stream().filter(m -> m.getTitle().equals("Naming")).findFirst().orElseThrow();
        assertEquals(projectKey(), naming.getProjectPath(), "imports from a project file stay project-scoped");
        assertTrue(all.stream().noneMatch(m -> m.getContent().lines().anyMatch(l -> l.strip().equals("@AGENTS.md"))),
                "import-only lines are dropped from the importing file's memories");
        assertTrue(all.stream().anyMatch(m -> m.getContent().contains("See @docs/style.md for conventions.")),
                "inline prose mentioning an import is kept");
        assertTrue(all.stream().anyMatch(m -> m.getContent().contains("Deep rule.")));
    }

    @Test
    @DisplayName("@import chains stop after five hops")
    void importDepthIsLimited() throws IOException {
        write(project.resolve("CLAUDE.md"), "Root.\n@chain/c1.md\n");
        for (int i = 1; i <= 7; i++) {
            write(project.resolve("chain/c" + i + ".md"), "Level " + i + "\n@c" + (i + 1) + ".md\n");
        }
        List<DetectedSource> sources = new AgentMemoryInterop(AgentEnvironment.isolated(home))
                .detect(project, List.of(CodingAgent.CLAUDE_CODE));
        for (int i = 1; i <= 5; i++) {
            assertTrue(source(sources, project.resolve("chain/c" + i + ".md")).isPresent(), "hop " + i);
        }
        assertTrue(source(sources, project.resolve("chain/c6.md")).isEmpty(), "hop 6 is beyond the limit");
    }

    @Test
    @DisplayName("@imports in GEMINI.md are followed; Codex AGENTS.md references are not")
    void geminiImportsButNotCodex() throws IOException {
        write(project.resolve("GEMINI.md"), "# Context\n@./rules/python-style.md\n");
        write(project.resolve("rules/python-style.md"), "Use black.");
        write(project.resolve("AGENTS.md"), "@./rules/codex-only.md\n");
        write(project.resolve("rules/codex-only.md"), "Codex does not expand imports.");

        List<DetectedSource> sources = new AgentMemoryInterop(AgentEnvironment.isolated(home)).detectAll(project);
        assertEquals(CodingAgent.GEMINI_CLI, source(sources, project.resolve("rules/python-style.md")).orElseThrow().agent());
        assertTrue(source(sources, project.resolve("rules/codex-only.md")).isEmpty());
    }

    @Test
    @DisplayName("Reference parsing trims punctuation and skips emails, annotations and handles")
    void referenceParsing() {
        List<String> refs = MemoryImports.findReferences(
                "Read @docs/a.md, then (@b.md). Mail me@example.com or ping @team. Use @Override. Also @~/x/y.md!");
        assertEquals(List.of("docs/a.md", "b.md", "~/x/y.md"), refs);
        assertEquals("keep\n- text", MemoryImports.stripImportLines("keep\n- @a.md @b/c.md\n- text"));
        assertEquals("@Override", MemoryImports.stripImportLines("@Override"), "non-path tokens are not import lines");
    }

    // ------------------------------------------------------------------
    // Claude Code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Claude: ~/.claude/rules is imported as user rules")
    void claudeUserRules() throws IOException {
        write(home.resolve(".claude/rules/security.md"), "Never commit secrets.");
        List<DetectedSource> sources = new AgentMemoryInterop(AgentEnvironment.isolated(home))
                .detect(project, List.of(CodingAgent.CLAUDE_CODE));
        DetectedSource rule = source(sources, home.resolve(".claude/rules/security.md")).orElseThrow();
        assertEquals(MemoryScope.USER, rule.location().scope());
        assertEquals(MemoryType.LONG_TERM, rule.location().type());
    }

    @Test
    @DisplayName("Claude: CLAUDE_CONFIG_DIR relocates user memory, rules, auto memory and the user export target")
    void claudeConfigDirOverride() throws IOException {
        Path custom = tmp.resolve("claude-config");
        write(home.resolve(".claude/CLAUDE.md"), "Default location must be ignored when overridden.");
        write(custom.resolve("CLAUDE.md"), "Custom user memory.");
        write(custom.resolve("rules/style.md"), "Tabs.");
        Path autoMem = custom.resolve("projects/" + CodingAgentCatalog.encodeClaudeProjectDir(project) + "/memory");
        write(autoMem.resolve("feedback_tests.md"), """
                ---
                name: Integration tests hit a real database
                description: Do not mock the database in integration tests
                type: feedback
                ---
                Use the dockerized Postgres for integration tests.
                """);
        write(autoMem.resolve("user_role.md"), "---\nname: User is a data scientist\ntype: user\n---\nFocus on observability.");

        AgentEnvironment env = AgentEnvironment.of(home, Map.of(AgentEnvironment.CLAUDE_CONFIG_DIR, custom.toString()));
        AgentMemoryInterop interop = new AgentMemoryInterop(env);
        List<DetectedSource> sources = interop.detect(project, List.of(CodingAgent.CLAUDE_CODE));

        assertTrue(source(sources, custom.resolve("CLAUDE.md")).isPresent());
        assertTrue(source(sources, custom.resolve("rules/style.md")).isPresent());
        assertTrue(source(sources, autoMem.resolve("feedback_tests.md")).isPresent());
        assertTrue(source(sources, home.resolve(".claude/CLAUDE.md")).isEmpty(), "~/.claude is not read when overridden");

        JsonAgentMemoryStore store = emptyStore();
        interop.importInto(store, sources, project, ImportOptions.defaults());
        MemoryItem feedback = store.getAllMemories().stream()
                .filter(m -> m.getTitle().equals("Integration tests hit a real database")).findFirst().orElseThrow();
        assertEquals(MemoryType.PROJECT_RULE, feedback.getType(), "feedback memories become rules");
        assertTrue(feedback.getTags().contains("feedback"));
        assertTrue(feedback.getContent().startsWith("Do not mock the database"), "description is kept");
        assertEquals(projectKey(), feedback.getProjectPath(), "auto memory belongs to its project");

        MemoryItem user = store.getAllMemories().stream()
                .filter(m -> m.getTitle().equals("User is a data scientist")).findFirst().orElseThrow();
        assertEquals(MemoryType.LONG_TERM, user.getType());
        assertTrue(user.getTags().contains("user-profile"));

        MemoryItem global = store.getAllMemories().stream()
                .filter(m -> m.getContent().contains("Custom user memory.")).findFirst().orElseThrow();
        assertNull(global.getProjectPath(), "user memory stays global");

        AgentMemoryInterop.ExportPlan plan = interop.planExport(CodingAgent.CLAUDE_CODE, MemoryScope.USER, project,
                List.of(new MemoryItem(MemoryType.LONG_TERM, "Fact", "x"))).orElseThrow();
        assertEquals(custom.resolve("CLAUDE.md").toAbsolutePath().normalize(), plan.target().toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------
    // OpenAI Codex
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Codex: CODEX_HOME relocates global AGENTS.md; project_doc_fallback_filenames adds project files")
    void codexHomeAndFallbacks() throws IOException {
        Path codexHome = tmp.resolve("codex-home");
        write(codexHome.resolve("AGENTS.md"), "## Global\nAlways run tests.\n");
        write(codexHome.resolve("config.toml"), """
                model = "gpt-5-codex"
                project_doc_fallback_filenames = ["TEAM_GUIDE.md", ".agents.md", "../escape.md"]

                [mcp_servers.docs]
                command = "docs-mcp"
                """);
        write(project.resolve("TEAM_GUIDE.md"), "## Reviews\nTwo approvals.\n");
        write(project.resolve(".agents.md"), "Hidden fallback.");
        write(project.getParent().resolve("escape.md"), "must not be read");

        AgentEnvironment env = AgentEnvironment.of(home, Map.of(AgentEnvironment.CODEX_HOME, codexHome.toString()));
        assertEquals(List.of("TEAM_GUIDE.md", ".agents.md"), env.codexFallbackFilenames(), "path-like names are dropped");

        AgentMemoryInterop interop = new AgentMemoryInterop(env);
        List<DetectedSource> sources = interop.detect(project, List.of(CodingAgent.CODEX));
        assertEquals(MemoryScope.USER, source(sources, codexHome.resolve("AGENTS.md")).orElseThrow().location().scope());
        assertEquals(MemoryScope.PROJECT, source(sources, project.resolve("TEAM_GUIDE.md")).orElseThrow().location().scope());
        assertTrue(source(sources, project.resolve(".agents.md")).isPresent());
        assertTrue(source(sources, project.getParent().resolve("escape.md")).isEmpty());

        AgentMemoryInterop.ExportPlan plan = interop.planExport(CodingAgent.CODEX, MemoryScope.USER, project,
                List.of(new MemoryItem(MemoryType.LONG_TERM, "Fact", "x"))).orElseThrow();
        assertEquals(codexHome.resolve("AGENTS.md").toAbsolutePath().normalize(), plan.target().toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------
    // Gemini CLI
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Gemini: GEMINI_CLI_HOME relocates ~/.gemini and context.fileName adds context files")
    void geminiHomeAndContextFileName() throws IOException {
        Path geminiCliHome = tmp.resolve("gemini-cli-home");
        write(geminiCliHome.resolve(".gemini/settings.json"), """
                {
                  // comments are allowed in Gemini CLI settings
                  "context": { "fileName": ["CONTEXT.md", "GEMINI.md"] },
                  "ui": { "theme": "GitHub" } /* trailing */
                }
                """);
        write(geminiCliHome.resolve(".gemini/GEMINI.md"), "## Gemini Added Memories\n- Deploy target is Cloud Run\n");
        write(geminiCliHome.resolve(".gemini/CONTEXT.md"), "Global custom context.");
        write(project.resolve("CONTEXT.md"), "## Architecture\nHexagonal.\n");

        AgentEnvironment env = AgentEnvironment.of(home, Map.of(AgentEnvironment.GEMINI_CLI_HOME, geminiCliHome.toString()));
        assertEquals(geminiCliHome.resolve(".gemini").toAbsolutePath().normalize(), env.geminiHome());
        assertEquals(List.of("CONTEXT.md", "GEMINI.md"), env.geminiContextFileNames(project));

        AgentMemoryInterop interop = new AgentMemoryInterop(env);
        List<DetectedSource> sources = interop.detect(project, List.of(CodingAgent.GEMINI_CLI));
        assertTrue(source(sources, geminiCliHome.resolve(".gemini/GEMINI.md")).isPresent());
        assertTrue(source(sources, geminiCliHome.resolve(".gemini/CONTEXT.md")).isPresent());
        assertEquals(MemoryScope.PROJECT, source(sources, project.resolve("CONTEXT.md")).orElseThrow().location().scope());

        JsonAgentMemoryStore store = emptyStore();
        interop.importInto(store, sources, project, ImportOptions.defaults());
        assertTrue(store.getAllMemories().stream().anyMatch(m -> m.getTitle().equals("Deploy target is Cloud Run")
                && m.getTags().contains("saved-memory")));
    }

    @Test
    @DisplayName("Gemini: workspace settings (legacy contextFileName) override user settings")
    void geminiWorkspaceSettingsWin() throws IOException {
        write(home.resolve(".gemini/settings.json"), "{\"context\":{\"fileName\":\"USER_CTX.md\"}}");
        write(project.resolve(".gemini/settings.json"), "{\"contextFileName\":\"TEAM.md\"}");
        write(project.resolve("TEAM.md"), "Team context.");
        write(project.resolve("USER_CTX.md"), "Not configured for this workspace.");

        AgentEnvironment env = AgentEnvironment.isolated(home);
        assertEquals(List.of("TEAM.md"), env.geminiContextFileNames(project));
        assertEquals(List.of("USER_CTX.md"), env.geminiContextFileNames(null));
        assertEquals(List.of("GEMINI.md"), AgentEnvironment.isolated(tmp.resolve("nobody")).geminiContextFileNames(null));

        List<DetectedSource> sources = new AgentMemoryInterop(env).detect(project, List.of(CodingAgent.GEMINI_CLI));
        assertTrue(source(sources, project.resolve("TEAM.md")).isPresent());
        assertTrue(source(sources, project.resolve("USER_CTX.md")).isEmpty());
    }

    @Test
    @DisplayName("Without overrides, agent homes default to ~/.claude, ~/.codex and ~/.gemini")
    void defaultHomes() {
        AgentEnvironment env = AgentEnvironment.of(home, Map.of(AgentEnvironment.CODEX_HOME, "  "));
        assertEquals(home.resolve(".claude"), env.claudeHome());
        assertEquals(home.resolve(".codex"), env.codexHome(), "blank values are ignored");
        assertEquals(home.resolve(".gemini"), env.geminiHome());
        for (CodingAgent agent : EnumSet.allOf(CodingAgent.class)) {
            for (CodingAgentCatalog.MemoryLocation loc : CodingAgentCatalog.locations(agent, env, project)) {
                assertNotNull(loc.resolve(project, env), agent + " " + loc.path() + " resolves");
            }
        }
    }
}

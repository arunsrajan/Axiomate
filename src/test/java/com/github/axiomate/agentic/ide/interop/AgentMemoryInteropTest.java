package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.agent.memory.JsonAgentMemoryStore;
import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.agent.memory.MemoryType;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.DetectedSource;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ExportPlan;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ImportOptions;
import com.github.axiomate.agentic.ide.interop.AgentMemoryInterop.ImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentMemoryInteropTest {

    @TempDir
    Path tmp;
    Path home;
    Path project;
    AgentMemoryInterop interop;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(tmp.resolve("home"));
        project = Files.createDirectories(tmp.resolve("work/my-app"));
        interop = new AgentMemoryInterop(home);
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

    @Test
    @DisplayName("Markdown parser splits at the shallowest repeated heading level and ignores code fences")
    void markdownSections() {
        MarkdownDocument doc = MarkdownDocument.parse("""
                ---
                description: "Team rules"
                globs: [src/**/*.java, test/**]
                ---
                # Project Guide
                Intro paragraph.

                ## Build
                Run `mvn test`.
                ```bash
                # not a heading
                mvn -q verify
                ```

                ## Style
                ### Naming
                Use camelCase.
                """);

        assertEquals("Team rules", doc.frontmatter("description"));
        assertEquals("src/**/*.java, test/**", doc.frontmatter("globs"));

        List<MarkdownDocument.Section> sections = doc.sections("fallback");
        assertEquals(3, sections.size());
        assertEquals("Project Guide", sections.get(0).title());
        assertEquals("Intro paragraph.", sections.get(0).content());
        assertEquals("Build", sections.get(1).title());
        assertTrue(sections.get(1).content().contains("# not a heading"), "code fence content stays inside section");
        assertEquals("Style", sections.get(2).title());
        assertTrue(sections.get(2).content().contains("### Naming"));
    }

    @Test
    @DisplayName("Managed block upsert preserves user content and is idempotent")
    void managedBlock() {
        String original = "# My CLAUDE.md\n\nHand-written rule.\n";
        String once = MarkdownDocument.upsertManagedBlock(original, "## Axiomate\nrule A");
        String twice = MarkdownDocument.upsertManagedBlock(once, "## Axiomate\nrule B");

        assertTrue(twice.startsWith("# My CLAUDE.md\n\nHand-written rule."));
        assertTrue(twice.contains("rule B"));
        assertFalse(twice.contains("rule A"));
        assertEquals(1, twice.split(MarkdownDocument.MANAGED_START, -1).length - 1);

        String removed = MarkdownDocument.upsertManagedBlock(twice, "");
        assertFalse(removed.contains(MarkdownDocument.MANAGED_START));
        assertTrue(removed.contains("Hand-written rule."));
        assertEquals("# My CLAUDE.md\n\nHand-written rule.\n", MarkdownDocument.stripManagedBlock(twice));
    }

    @Test
    @DisplayName("Detects memory files for Claude Code, Codex, Cursor, Antigravity, Gemini, Windsurf and Copilot")
    void detectAcrossAgents() throws IOException {
        write(project.resolve("CLAUDE.md"), "# Rules\n## Testing\nUse JUnit 5.\n## Style\nNo wildcard imports.\n");
        write(project.resolve(".claude/rules/security.md"), "Never log secrets.");
        write(project.resolve("AGENTS.md"), "## Commands\nmvn test\n## PRs\nSmall diffs.\n");
        write(project.resolve(".cursor/rules/java.mdc"), "---\ndescription: Java rules\nglobs: \"**/*.java\"\nalwaysApply: false\n---\nPrefer records.");
        write(project.resolve(".agents/rules/deploy.md"), "---\ntrigger: always_on\n---\nDeploy via CI only.");
        write(project.resolve(".windsurf/rules/tests.md"), "---\ntrigger: model_decision\ndescription: Testing guidance\n---\nMock the network.");
        write(project.resolve(".github/copilot-instructions.md"), "Use Java 21.");
        write(home.resolve(".gemini/GEMINI.md"), "## Gemini Added Memories\n- My name is Sam\n- Prefer tabs over spaces\n");
        write(home.resolve(".claude/CLAUDE.md"), "Always answer concisely.");
        Path autoMem = home.resolve(".claude/projects/" + CodingAgentCatalog.encodeClaudeProjectDir(project) + "/memory");
        write(autoMem.resolve("MEMORY.md"), "- [Build](build.md) — how to build\n");
        write(autoMem.resolve("build.md"), "---\nname: Build command\ndescription: How to build\ntype: project\n---\nUse ./mvnw.");

        List<DetectedSource> sources = interop.detectAll(project);
        Set<CodingAgent> agents = EnumSet.noneOf(CodingAgent.class);
        sources.forEach(s -> agents.add(s.agent()));

        assertTrue(agents.containsAll(EnumSet.of(CodingAgent.CLAUDE_CODE, CodingAgent.CODEX, CodingAgent.CURSOR,
                CodingAgent.ANTIGRAVITY, CodingAgent.WINDSURF, CodingAgent.GITHUB_COPILOT)));
        // ~/.gemini/GEMINI.md is shared by Antigravity and Gemini CLI, but reported once
        assertEquals(1, sources.stream().filter(s -> s.file().endsWith("GEMINI.md")).count());
        // Claude auto-memory index is skipped when topic files exist
        assertTrue(sources.stream().noneMatch(s -> s.file().getFileName().toString().equals("MEMORY.md")));
        assertTrue(sources.stream().anyMatch(s -> s.file().getFileName().toString().equals("build.md")));
    }

    @Test
    @DisplayName("Import converts files to scoped memories and re-import replaces instead of duplicating")
    void importIsIdempotentAndScoped() throws IOException {
        write(project.resolve("CLAUDE.md"), "# Rules\n## Testing\nUse JUnit 5.\n## Style\nNo wildcard imports.\n");
        write(project.resolve(".cursor/rules/java.mdc"), "---\ndescription: Java rules\nglobs: \"**/*.java\"\nalwaysApply: true\n---\nPrefer records.");
        write(home.resolve(".gemini/GEMINI.md"), "## Gemini Added Memories\n- My name is Sam\n- Prefer tabs over spaces\n");

        JsonAgentMemoryStore store = emptyStore();
        List<DetectedSource> sources = interop.detectAll(project);
        ImportResult first = interop.importInto(store, sources, project, ImportOptions.defaults());
        assertEquals(5, first.imported(), "2 CLAUDE.md sections + 1 cursor rule + 2 Gemini saved memories");
        assertEquals(5, store.getAllMemories().size());

        MemoryItem testing = store.getAllMemories().stream().filter(m -> m.getTitle().equals("Testing")).findFirst().orElseThrow();
        assertEquals(MemoryType.PROJECT_RULE, testing.getType());
        assertEquals(ProjectStateManager.normalizePath(project.toFile()), testing.getProjectPath());
        assertTrue(testing.getTags().contains("claude-code"));

        MemoryItem cursorRule = store.getAllMemories().stream().filter(m -> m.getTitle().equals("Java rules")).findFirst().orElseThrow();
        assertTrue(cursorRule.getContent().contains("Applies to: **/*.java"));
        assertTrue(cursorRule.getTags().contains("always-on"));

        MemoryItem saved = store.getAllMemories().stream().filter(m -> m.getTitle().equals("My name is Sam")).findFirst().orElseThrow();
        assertEquals(MemoryType.LONG_TERM, saved.getType());
        assertNull(saved.getProjectPath(), "user-level memories stay global");

        // Edit CLAUDE.md and re-import: old sections are replaced, not duplicated
        write(project.resolve("CLAUDE.md"), "# Rules\n## Testing\nUse JUnit 5 and AssertJ.\n");
        ImportResult second = interop.importInto(store, interop.detectAll(project), project, ImportOptions.defaults());
        assertEquals(4, store.getAllMemories().size());
        assertTrue(second.replaced() >= 5);
        assertTrue(store.getAllMemories().stream().anyMatch(m -> m.getContent().contains("AssertJ")));
        assertTrue(store.getAllMemories().stream().noneMatch(m -> m.getTitle().equals("Style")));
    }

    @Test
    @DisplayName("Project-scoped memories are only visible to their own project")
    void projectScopeFiltersRetrieval() throws IOException {
        write(project.resolve("AGENTS.md"), "## Deploy\nOnly deploy on Fridays with the zebra checklist.\n## Lint\nRun spotless.\n");
        JsonAgentMemoryStore store = emptyStore();
        interop.importInto(store, interop.detectAll(project), project, ImportOptions.defaults());

        store.setActiveProjectScope(ProjectStateManager.normalizePath(project.toFile()));
        assertFalse(store.search("zebra", 5).isEmpty());

        store.setActiveProjectScope(ProjectStateManager.normalizePath(tmp.resolve("other").toFile()));
        assertTrue(store.search("zebra", 5).isEmpty(), "other project must not see these rules");
        assertFalse(store.getRelevantContext("zebra").contains("zebra"));
    }

    @Test
    @DisplayName("Export writes native files: managed block for CLAUDE.md, dedicated rules for Cursor/Antigravity")
    void exportToAgents() throws IOException {
        write(project.resolve("CLAUDE.md"), "# Team notes\n\nKeep this line.\n");
        JsonAgentMemoryStore store = emptyStore();
        MemoryItem rule = new MemoryItem(MemoryType.PROJECT_RULE, "Use records", "Prefer Java records for DTOs.\n## Nested heading");
        MemoryItem fact = new MemoryItem(MemoryType.LONG_TERM, "Build", "Use ./mvnw verify");
        MemoryItem episode = new MemoryItem(MemoryType.EPISODIC, "Task: fix bug", "done");
        store.addMemories(List.of(rule, fact, episode));

        List<MemoryItem> selected = AgentMemoryInterop.selectForExport(store.getAllMemories(), project, MemoryScope.PROJECT,
                EnumSet.of(MemoryType.PROJECT_RULE, MemoryType.LONG_TERM));
        assertEquals(2, selected.size());

        ExportPlan claude = interop.planExport(CodingAgent.CLAUDE_CODE, MemoryScope.PROJECT, project, selected).orElseThrow();
        interop.apply(claude);
        String claudeMd = Files.readString(project.resolve("CLAUDE.md"));
        assertTrue(claudeMd.startsWith("# Team notes\n\nKeep this line."));
        assertTrue(claudeMd.contains("#### Use records"));
        assertTrue(claudeMd.contains("##### Nested heading"), "item headings are demoted below the item title");
        // Second export replaces the block
        interop.apply(interop.planExport(CodingAgent.CLAUDE_CODE, MemoryScope.PROJECT, project, selected).orElseThrow());
        assertEquals(1, Files.readString(project.resolve("CLAUDE.md")).split(MarkdownDocument.MANAGED_START, -1).length - 1);

        interop.apply(interop.planExport(CodingAgent.CURSOR, MemoryScope.PROJECT, project, selected).orElseThrow());
        String mdc = Files.readString(project.resolve(".cursor/rules/axiomate-memory.mdc"));
        assertTrue(mdc.startsWith("---\ndescription:"));
        assertTrue(mdc.contains("alwaysApply: true"));

        interop.apply(interop.planExport(CodingAgent.ANTIGRAVITY, MemoryScope.PROJECT, project, selected).orElseThrow());
        assertTrue(Files.readString(project.resolve(".agents/rules/axiomate-memory.md")).contains("trigger: always_on"));

        assertTrue(interop.planExport(CodingAgent.CURSOR, MemoryScope.USER, project, selected).isEmpty(),
                "Cursor user rules are not file based");

        // Exported content is not re-imported (would duplicate), unless explicitly requested
        List<DetectedSource> again = interop.detectAll(project);
        JsonAgentMemoryStore fresh = new JsonAgentMemoryStore(tmp.resolve("fresh.json"));
        fresh.clear();
        interop.importInto(fresh, again, project, ImportOptions.defaults());
        assertTrue(fresh.getAllMemories().stream().noneMatch(m -> m.getTitle().equals("Use records")));
        assertTrue(fresh.getAllMemories().stream().anyMatch(m -> m.getContent().contains("Keep this line.")));
    }

    @Test
    @DisplayName("Exporting to a file never writes that file's own imported memories back into it")
    void exportSkipsSelfSourcedMemories() throws IOException {
        write(project.resolve("AGENTS.md"), "## Lint\nRun spotless.\n## Test\nRun mvn test.\n");
        JsonAgentMemoryStore store = emptyStore();
        interop.importInto(store, interop.detect(project, List.of(CodingAgent.CODEX)), project, ImportOptions.defaults());
        store.addMemory(new MemoryItem(MemoryType.PROJECT_RULE, "Axiomate-only rule", "Keep PRs small."));

        List<MemoryItem> selected = AgentMemoryInterop.selectForExport(store.getAllMemories(), project, MemoryScope.PROJECT,
                EnumSet.of(MemoryType.PROJECT_RULE));
        ExportPlan plan = interop.planExport(CodingAgent.CODEX, MemoryScope.PROJECT, project, selected).orElseThrow();
        assertEquals(1, plan.itemCount());
        assertFalse(plan.newContent().contains("#### Lint"));
        assertTrue(plan.newContent().contains("#### Axiomate-only rule"));
    }

    @Test
    @DisplayName("Every agent has import locations and at least a project export target or documented gap")
    void catalogCoverage() {
        for (CodingAgent agent : CodingAgent.values()) {
            assertFalse(CodingAgentCatalog.locations(agent).isEmpty(), agent + " has import locations");
            assertTrue(CodingAgentCatalog.exportTarget(agent, MemoryScope.PROJECT).isPresent(), agent + " has a project export target");
        }
        assertEquals("-tmp-x-my-app", CodingAgentCatalog.encodeClaudeProjectDir(Path.of("/tmp/x/my_app")));
    }
}

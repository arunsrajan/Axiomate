package com.github.axiomate.agentic.ide.plugins;

import com.github.axiomate.agentic.ide.agent.memory.MemoryItem;
import com.github.axiomate.agentic.ide.interop.AgentCommandInterop;
import com.github.axiomate.agentic.ide.interop.CodingAgent;
import com.github.axiomate.agentic.ide.mcp.McpServerConfig;
import com.github.axiomate.agentic.ide.mcp.McpTransport;
import com.github.axiomate.agentic.ide.plugins.PluginManager.InstallOptions;
import com.github.axiomate.agentic.ide.plugins.PluginPackageReader.PluginPackage;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.Dispatch;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.Outcome;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    /** In-memory host so tests never touch the real ~/.axiomate-ide stores. */
    static class FakeHost implements PluginHost {
        final List<MemoryItem> memories = new ArrayList<>();
        final Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        final SlashCommandRegistry registry = new SlashCommandRegistry();

        @Override
        public void addMemories(List<MemoryItem> items) {
            for (MemoryItem i : items) {
                memories.removeIf(m -> m.getId().equals(i.getId()));
                memories.add(i);
            }
        }

        @Override
        public int removeMemoriesBySource(String source) {
            int before = memories.size();
            memories.removeIf(m -> source.equals(m.getSource()));
            return before - memories.size();
        }

        @Override
        public boolean hasMcpServer(String name) {
            return servers.containsKey(name);
        }

        @Override
        public void addMcpServer(McpServerConfig config) {
            servers.put(config.getName(), config);
        }

        @Override
        public void removeMcpServer(String name) {
            servers.remove(name);
        }

        @Override
        public void setMcpServerEnabled(String name, boolean enabled) {
            McpServerConfig c = servers.get(name);
            if (c != null) c.setEnabled(enabled);
        }

        @Override
        public SlashCommandRegistry commands() {
            return registry;
        }
    }

    @TempDir
    Path tmp;
    FakeHost host;
    PluginManager manager;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        manager = new PluginManager(tmp.resolve("plugins"), host);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("Catalog plugins install memories, commands and (stopped) MCP servers with env and project vars")
    void installFromCatalog() throws IOException {
        PluginManifest github = manager.getCatalog().stream().filter(p -> p.id().equals("mcp-github")).findFirst().orElseThrow();
        manager.install(github, new InstallOptions(false, Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", "ghp_test"), tmp));
        McpServerConfig gh = host.servers.get("github");
        assertNotNull(gh);
        assertFalse(gh.isEnabled(), "MCP servers stay stopped unless the user opts in");
        assertEquals("ghp_test", gh.getEnv().get("GITHUB_PERSONAL_ACCESS_TOKEN"));

        PluginManifest fs = manager.getCatalog().stream().filter(p -> p.id().equals("mcp-filesystem")).findFirst().orElseThrow();
        manager.install(fs, new InstallOptions(true, Map.of(), tmp));
        assertTrue(host.servers.get("filesystem").getArgs().contains(tmp.toAbsolutePath().toString()), "${PROJECT_ROOT} expanded");
        assertTrue(host.servers.get("filesystem").isEnabled());

        PluginManifest java = manager.getCatalog().stream().filter(p -> p.id().equals("rules-java-modern")).findFirst().orElseThrow();
        manager.install(java, InstallOptions.defaults(tmp));
        assertEquals(4, host.memories.size());
        assertTrue(host.memories.stream().allMatch(m -> "plugin:rules-java-modern".equals(m.getSource())));
        assertNotNull(host.registry.find("modernize"));

        // Registry is persisted and commands come back after a restart
        FakeHost restartedHost = new FakeHost();
        PluginManager reloaded = new PluginManager(tmp.resolve("plugins"), restartedHost);
        assertEquals(3, reloaded.getInstalled().size());
        assertNotNull(restartedHost.registry.find("modernize"));
    }

    @Test
    @DisplayName("Disable retracts contributions, enable restores them, uninstall removes everything")
    void lifecycle() throws IOException {
        PluginManifest review = manager.getCatalog().stream().filter(p -> p.id().equals("rules-secure-coding")).findFirst().orElseThrow();
        manager.install(review, InstallOptions.defaults(tmp));
        PluginManifest fetch = manager.getCatalog().stream().filter(p -> p.id().equals("mcp-fetch")).findFirst().orElseThrow();
        manager.install(fetch, new InstallOptions(true, Map.of(), tmp));

        manager.setEnabled("rules-secure-coding", false);
        assertTrue(host.memories.isEmpty());
        assertNull(host.registry.find("security-review"));
        manager.setEnabled("rules-secure-coding", true);
        assertEquals(3, host.memories.size());
        assertNotNull(host.registry.find("security-review"));

        manager.setEnabled("mcp-fetch", false);
        assertFalse(host.servers.get("fetch").isEnabled(), "disabling keeps config but stops the server");
        manager.setEnabled("mcp-fetch", true);
        assertTrue(host.servers.get("fetch").isEnabled());
        manager.setMcpServersEnabled("mcp-fetch", false);
        assertFalse(host.servers.get("fetch").isEnabled());

        manager.uninstall("mcp-fetch");
        manager.uninstall("rules-secure-coding");
        assertTrue(host.servers.isEmpty());
        assertTrue(host.memories.isEmpty());
        assertTrue(manager.getInstalled().isEmpty());
        assertFalse(Files.exists(tmp.resolve("plugins/mcp-fetch")));
    }

    @Test
    @DisplayName("Plugin MCP servers never clobber an existing server with the same name")
    void serverNameCollision() throws IOException {
        host.addMcpServer(new McpServerConfig("git", "my-own-git", List.of()));
        PluginManifest git = manager.getCatalog().stream().filter(p -> p.id().equals("mcp-git")).findFirst().orElseThrow();
        InstalledPlugin p = manager.install(git, InstallOptions.defaults(tmp));
        assertEquals("my-own-git", host.servers.get("git").getCommand());
        assertEquals(List.of("mcp-git-git"), p.mcpServerNames());
        manager.uninstall("mcp-git");
        assertTrue(host.servers.containsKey("git"), "user's own server is untouched");
    }

    @Test
    @DisplayName("Claude Code plugins convert commands, agents, skills and .mcp.json")
    void claudeCodePlugin() throws IOException {
        Path dir = tmp.resolve("src/feature-dev");
        write(dir.resolve(".claude-plugin/plugin.json"), """
                {"name": "feature-dev", "version": "1.2.0", "description": "Feature workflow", "author": {"name": "Anthropic"}}
                """);
        write(dir.resolve("commands/feature.md"), "---\ndescription: Build a feature\n---\nPlan and implement: $ARGUMENTS");
        write(dir.resolve("commands/git/commit.md"), "Write a commit message");
        write(dir.resolve("agents/code-reviewer.md"), "---\nname: code-reviewer\ndescription: Reviews code\n---\nYou review code carefully.");
        write(dir.resolve("skills/testing/SKILL.md"), "---\nname: testing\ndescription: How we test\n---\nUse JUnit 5.");
        write(dir.resolve(".mcp.json"), """
                {"mcpServers": {"helper": {"command": "${CLAUDE_PLUGIN_ROOT}/bin/helper", "args": ["--x"], "env": {"TOKEN": "${MY_TOKEN}"}}}}
                """);
        write(dir.resolve("hooks/hooks.json"), "{}");

        PluginPackage pkg = manager.readPackage(dir);
        assertEquals(PluginPackageReader.FORMAT_CLAUDE_CODE, pkg.format());
        assertEquals("feature-dev", pkg.manifest().id());
        assertEquals("Anthropic", pkg.manifest().author());
        assertTrue(pkg.warnings().stream().anyMatch(w -> w.contains("Hooks")));
        assertEquals(List.of("TOKEN"), pkg.manifest().mcpServers().get(0).requiredEnv());

        InstalledPlugin installed = manager.installPackage(pkg, dir.toString(), new InstallOptions(false, Map.of("TOKEN", "secret"), tmp));
        assertNotNull(host.registry.find("feature"));
        assertNotNull(host.registry.find("git:commit"), "nested commands are namespaced");
        assertTrue(host.registry.find("code-reviewer").template().contains("You review code carefully."));
        assertEquals("Plan and implement: add login", host.registry.find("feature").expand("add login"));
        assertTrue(host.memories.stream().anyMatch(m -> m.getTitle().equals("Skill: testing")));

        McpServerConfig helper = host.servers.get("helper");
        Path installDir = tmp.resolve("plugins/feature-dev").toAbsolutePath();
        assertEquals(installDir + "/bin/helper", helper.getCommand(), "${CLAUDE_PLUGIN_ROOT} expands to the install folder");
        assertEquals("secret", helper.getEnv().get("TOKEN"));
        assertTrue(Files.exists(Path.of(installed.installPath()).resolve("commands/feature.md")), "files copied into plugin folder");
    }

    @Test
    @DisplayName("ZIP installs unwrap the archive's top folder and reject path traversal")
    void zipInstallAndZipSlip() throws IOException {
        Path zip = tmp.resolve("plugin.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("my-plugin-main/axiomate-plugin.json"));
            zos.write("""
                    {"id": "my-plugin", "name": "My Plugin", "version": "2.0.0",
                     "commands": [{"name": "hello", "description": "Say hi", "prompt": "Say hello to $1"}],
                     "memories": [{"title": "Greeting rule", "content": "Be friendly", "type": "PROJECT_RULE"}]}
                    """.getBytes());
            zos.closeEntry();
        }
        InstalledPlugin p = manager.installFromPath(zip, InstallOptions.defaults(tmp));
        assertEquals("my-plugin", p.id());
        assertEquals("Say hello to Ada", host.registry.find("hello").expand("Ada Lovelace"));
        assertEquals(1, host.memories.size());

        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bad)) {
            zos.putNextEntry(new ZipEntry("../../evil.txt"));
            zos.write("x".getBytes());
            zos.closeEntry();
        }
        Path target = tmp.resolve("extract");
        IOException ex = assertThrows(IOException.class,
                () -> PluginPackageReader.extractZip(new ByteArrayInputStream(bad.toByteArray()), target));
        assertTrue(ex.getMessage().contains("unsafe"));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test
    @DisplayName("Plugin ids can never escape the plugins folder")
    void maliciousIdsAreSanitized() throws IOException {
        Path victim = Files.createDirectories(tmp.resolve("victim"));
        Files.writeString(victim.resolve("keep.txt"), "important");
        Path dir = tmp.resolve("evil-plugin");
        write(dir.resolve("axiomate-plugin.json"), "{\"id\": \"../victim\", \"name\": \"Evil\"}");
        InstalledPlugin p = manager.installFromPath(dir, InstallOptions.defaults(tmp));
        assertTrue(Files.exists(victim.resolve("keep.txt")), "files outside the plugins folder are untouched");
        assertTrue(Path.of(p.installPath()).startsWith(tmp.resolve("plugins").toAbsolutePath()));
        for (String raw : List.of("..", ".", "../..", "/etc/passwd", "a/../../b")) {
            String id = PluginPackageReader.sanitizeId(raw);
            assertFalse(id.contains("/") || id.equals("..") || id.equals(".") || id.startsWith("."), raw + " -> " + id);
        }
    }

    @Test
    @DisplayName("Plugin URLs resolve GitHub repos, GitHub sub-folders and direct zip links")
    void resolveUrls() {
        var repo = PluginPackageReader.resolveUrl("https://github.com/acme/tools");
        assertEquals("https://github.com/acme/tools/archive/HEAD.zip", repo.archiveUri().toString());
        assertNull(repo.subPath());

        var folder = PluginPackageReader.resolveUrl("https://github.com/anthropics/claude-code/tree/main/plugins/feature-dev/");
        assertEquals("https://github.com/anthropics/claude-code/archive/main.zip", folder.archiveUri().toString());
        assertEquals("plugins/feature-dev", folder.subPath());

        assertEquals("https://example.com/p.zip", PluginPackageReader.resolveUrl("https://example.com/p.zip").archiveUri().toString());
        assertThrows(IllegalArgumentException.class, () -> PluginPackageReader.resolveUrl("http://example.com/p.zip"));
        assertThrows(IllegalArgumentException.class, () -> PluginPackageReader.resolveUrl("https://example.com/page"));
    }

    @Test
    @DisplayName("Slash command registry expands templates, runs actions and protects built-ins")
    void slashCommands() {
        SlashCommandRegistry reg = new SlashCommandRegistry();
        AtomicReference<String> ran = new AtomicReference<>();
        reg.register(new SlashCommand("clear", "Clear chat", null, "builtin", ran::set));
        reg.register(new SlashCommand("clear", "Shadow", "evil", "plugin:x"));
        reg.register(new SlashCommand("/Review", "Review", "Review $ARGUMENTS carefully", "plugin:x"));
        reg.register(new SlashCommand("summarize", "Summarize", "Summarize the text", "plugin:x"));

        Dispatch d = reg.dispatch("/clear now");
        assertEquals(Outcome.EXECUTED, d.outcome());
        assertEquals("now", ran.get());

        assertEquals("Review Foo.java carefully", reg.dispatch("/review Foo.java").prompt());
        assertEquals("Summarize the text\n\nabc", reg.dispatch("/summarize abc").prompt(), "args appended when no placeholder");
        assertEquals(Outcome.UNKNOWN, reg.dispatch("/nope").outcome());
        assertEquals(Outcome.NOT_A_COMMAND, reg.dispatch("/usr/bin/ls is a path").outcome());
        assertEquals(Outcome.NOT_A_COMMAND, reg.dispatch("hello /review").outcome());
        assertEquals(2, reg.complete("re").size() + reg.complete("su").size());

        assertEquals(2, reg.unregisterSource("plugin:x"));
        assertNotNull(reg.find("clear"));
    }

    @Test
    @DisplayName("Discovers slash commands from Claude Code, Codex, Antigravity and Gemini CLI folders")
    void agentCommands() throws IOException {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path project = Files.createDirectories(tmp.resolve("proj"));
        write(project.resolve(".claude/commands/fix-issue.md"), "---\ndescription: Fix a GitHub issue\n---\nFix issue #$ARGUMENTS");
        write(home.resolve(".claude/commands/fix-issue.md"), "user-level version");
        write(home.resolve(".codex/prompts/draftpr.md"), "Draft a PR for $1");
        write(project.resolve(".agents/workflows/deploy.md"), "---\ndescription: Deploy\n---\n1. Build\n2. Deploy");
        write(project.resolve(".gemini/commands/git/summary.toml"), "description = \"Summarize\"\nprompt = \"\"\"Summarize {{args}}\"\"\"\n");

        List<SlashCommand> cmds = new AgentCommandInterop(home).discover(project);
        Map<String, SlashCommand> byName = new HashMap<>();
        cmds.forEach(c -> byName.put(c.name(), c));

        assertEquals("Fix issue #$ARGUMENTS", byName.get("fix-issue").template(), "project overrides user scope");
        assertEquals(AgentCommandInterop.sourceKey(CodingAgent.CLAUDE_CODE), byName.get("fix-issue").source());
        assertEquals("Draft a PR for 42", byName.get("draftpr").expand("42"));
        assertTrue(byName.get("deploy").template().contains("1. Build"));
        assertEquals("Summarize HEAD~3", byName.get("git:summary").expand("HEAD~3"));
    }

    @Test
    @DisplayName("Manifest MCP contribution drops unresolved env placeholders so inherited values win")
    void envPlaceholders() {
        var sc = new PluginManifest.McpServerContribution("s", "STDIO", "cmd", List.of(), Map.of("A", "${A}", "B", "fixed"),
                null, null, List.of("A"));
        McpServerConfig cfg = sc.toConfig("s", Map.of(), Map.of());
        assertEquals(Map.of("B", "fixed"), cfg.getEnv());
        assertEquals(McpTransport.STDIO, cfg.getTransport());
        assertEquals("cmd", sc.launchSummary());
    }
}

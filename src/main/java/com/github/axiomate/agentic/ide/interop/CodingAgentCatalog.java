package com.github.axiomate.agentic.ide.interop;

import com.github.axiomate.agentic.ide.agent.memory.MemoryType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data-driven catalog of the on-disk memory / rule locations used by each supported coding agent,
 * and the file Axiomate writes when exporting memories back to that agent.
 * <p>
 * Paths are relative to the project root ({@link MemoryScope#PROJECT}) or the user's home directory
 * ({@link MemoryScope#USER}). The token {@value #CLAUDE_PROJECT_TOKEN} expands to Claude Code's
 * encoded project directory name under {@code ~/.claude/projects}.
 */
public final class CodingAgentCatalog {

    public static final String CLAUDE_PROJECT_TOKEN = "{claudeProject}";
    public static final String EXPORT_FILE_BASENAME = "axiomate-memory";

    /**
     * A file or directory where an agent keeps instructions or memories.
     *
     * @param directory     true when every matching file inside {@code path} is a memory source
     * @param suffixes      file suffixes accepted for directory scans
     * @param splitSections split a file into one memory per Markdown section (false: one memory per file)
     * @param indexFile     for directories, a file to skip when sibling topic files exist (e.g. MEMORY.md)
     */
    public record MemoryLocation(CodingAgent agent, MemoryScope scope, String path, boolean directory,
                                 List<String> suffixes, MemoryType type, boolean splitSections,
                                 String indexFile, String description) {

        public Path resolve(Path projectDir, Path homeDir) {
            Path base = scope == MemoryScope.PROJECT ? projectDir : homeDir;
            if (base == null) return null;
            String p = path;
            if (p.contains(CLAUDE_PROJECT_TOKEN)) {
                if (projectDir == null) return null;
                p = p.replace(CLAUDE_PROJECT_TOKEN, encodeClaudeProjectDir(projectDir));
            }
            return base.resolve(p);
        }

        public boolean accepts(String fileName) {
            String lower = fileName.toLowerCase();
            for (String s : suffixes) {
                if (lower.endsWith(s)) return true;
            }
            return false;
        }
    }

    public enum ExportStyle {
        /** Insert/replace a marker-delimited block, preserving the rest of a shared file. */
        MANAGED_BLOCK,
        /** Write a dedicated rule file owned entirely by Axiomate. */
        DEDICATED_FILE
    }

    /**
     * The file Axiomate writes when exporting memories to an agent.
     *
     * @param frontmatter YAML frontmatter (without delimiters) required by dedicated rule files, or null
     */
    public record ExportTarget(CodingAgent agent, MemoryScope scope, String path, ExportStyle style,
                               String frontmatter) {

        public Path resolve(Path projectDir, Path homeDir) {
            Path base = scope == MemoryScope.PROJECT ? projectDir : homeDir;
            return base == null ? null : base.resolve(path);
        }
    }

    private static final List<String> MD = List.of(".md");

    private CodingAgentCatalog() {
    }

    public static List<MemoryLocation> locations(CodingAgent agent) {
        List<MemoryLocation> l = new ArrayList<>();
        switch (agent) {
            case CLAUDE_CODE -> {
                l.add(file(agent, MemoryScope.PROJECT, "CLAUDE.md", MemoryType.PROJECT_RULE, "Project memory"));
                l.add(file(agent, MemoryScope.PROJECT, ".claude/CLAUDE.md", MemoryType.PROJECT_RULE, "Project memory (.claude)"));
                l.add(file(agent, MemoryScope.PROJECT, "CLAUDE.local.md", MemoryType.PROJECT_RULE, "Local project memory"));
                l.add(dir(agent, MemoryScope.PROJECT, ".claude/rules", MD, MemoryType.PROJECT_RULE, null, "Modular project rules"));
                l.add(file(agent, MemoryScope.USER, ".claude/CLAUDE.md", MemoryType.LONG_TERM, "User memory"));
                l.add(dir(agent, MemoryScope.USER, ".claude/projects/" + CLAUDE_PROJECT_TOKEN + "/memory", MD,
                        MemoryType.LONG_TERM, "MEMORY.md", "Auto memory for this project"));
            }
            case CODEX -> {
                l.add(file(agent, MemoryScope.PROJECT, "AGENTS.md", MemoryType.PROJECT_RULE, "AGENTS.md (shared by Codex, Cursor, Antigravity, Copilot...)"));
                l.add(file(agent, MemoryScope.PROJECT, "AGENTS.override.md", MemoryType.PROJECT_RULE, "Override instructions"));
                l.add(file(agent, MemoryScope.USER, ".codex/AGENTS.md", MemoryType.LONG_TERM, "Global instructions"));
                l.add(file(agent, MemoryScope.USER, ".codex/AGENTS.override.md", MemoryType.LONG_TERM, "Global override instructions"));
            }
            case CURSOR -> {
                l.add(dir(agent, MemoryScope.PROJECT, ".cursor/rules", List.of(".mdc", ".md"), MemoryType.PROJECT_RULE, null, "Project rules (.mdc)"));
                l.add(file(agent, MemoryScope.PROJECT, ".cursorrules", MemoryType.PROJECT_RULE, "Legacy .cursorrules"));
            }
            case ANTIGRAVITY -> {
                l.add(dir(agent, MemoryScope.PROJECT, ".agents/rules", MD, MemoryType.PROJECT_RULE, null, "Workspace rules"));
                l.add(dir(agent, MemoryScope.PROJECT, ".agent/rules", MD, MemoryType.PROJECT_RULE, null, "Workspace rules (legacy .agent)"));
                l.add(file(agent, MemoryScope.USER, ".gemini/GEMINI.md", MemoryType.LONG_TERM, "Global rules (shared with Gemini CLI)"));
                l.add(file(agent, MemoryScope.USER, ".gemini/AGENTS.md", MemoryType.LONG_TERM, "Global AGENTS.md"));
                l.add(dir(agent, MemoryScope.USER, ".gemini/config/rules", MD, MemoryType.LONG_TERM, null, "Global per-file rules"));
            }
            case GEMINI_CLI -> {
                l.add(file(agent, MemoryScope.PROJECT, "GEMINI.md", MemoryType.PROJECT_RULE, "Project context"));
                l.add(file(agent, MemoryScope.PROJECT, ".gemini/GEMINI.md", MemoryType.PROJECT_RULE, "Project context (.gemini)"));
                l.add(file(agent, MemoryScope.USER, ".gemini/GEMINI.md", MemoryType.LONG_TERM, "Global context & saved memories"));
            }
            case WINDSURF -> {
                l.add(dir(agent, MemoryScope.PROJECT, ".windsurf/rules", MD, MemoryType.PROJECT_RULE, null, "Workspace rules"));
                l.add(dir(agent, MemoryScope.PROJECT, ".devin/rules", MD, MemoryType.PROJECT_RULE, null, "Workspace rules (.devin)"));
                l.add(file(agent, MemoryScope.PROJECT, ".windsurfrules", MemoryType.PROJECT_RULE, "Legacy .windsurfrules"));
                l.add(file(agent, MemoryScope.USER, ".codeium/windsurf/memories/global_rules.md", MemoryType.LONG_TERM, "Global rules"));
            }
            case GITHUB_COPILOT -> {
                l.add(file(agent, MemoryScope.PROJECT, ".github/copilot-instructions.md", MemoryType.PROJECT_RULE, "Repository instructions"));
                l.add(dir(agent, MemoryScope.PROJECT, ".github/instructions", List.of(".instructions.md"), MemoryType.PROJECT_RULE, null, "Path-specific instructions"));
            }
            case CLINE -> {
                l.add(file(agent, MemoryScope.PROJECT, ".clinerules", MemoryType.PROJECT_RULE, "Rules file"));
                l.add(dir(agent, MemoryScope.PROJECT, ".clinerules", MD, MemoryType.PROJECT_RULE, null, "Rules folder"));
                l.add(dir(agent, MemoryScope.PROJECT, "memory-bank", MD, MemoryType.LONG_TERM, null, "Memory bank"));
                l.add(dir(agent, MemoryScope.USER, "Documents/Cline/Rules", MD, MemoryType.LONG_TERM, null, "Global rules"));
            }
            case ROO_CODE -> {
                l.add(dir(agent, MemoryScope.PROJECT, ".roo/rules", MD, MemoryType.PROJECT_RULE, null, "Workspace rules"));
                l.add(file(agent, MemoryScope.PROJECT, ".roorules", MemoryType.PROJECT_RULE, "Legacy .roorules"));
                l.add(dir(agent, MemoryScope.USER, ".roo/rules", MD, MemoryType.LONG_TERM, null, "Global rules"));
            }
            case KIRO -> {
                l.add(dir(agent, MemoryScope.PROJECT, ".kiro/steering", MD, MemoryType.PROJECT_RULE, null, "Steering files"));
                l.add(dir(agent, MemoryScope.USER, ".kiro/steering", MD, MemoryType.LONG_TERM, null, "Global steering"));
            }
        }
        return l;
    }

    public static Optional<ExportTarget> exportTarget(CodingAgent agent, MemoryScope scope) {
        boolean project = scope == MemoryScope.PROJECT;
        ExportTarget t = switch (agent) {
            case CLAUDE_CODE -> managed(agent, scope, project ? "CLAUDE.md" : ".claude/CLAUDE.md");
            case CODEX -> managed(agent, scope, project ? "AGENTS.md" : ".codex/AGENTS.md");
            case GEMINI_CLI -> managed(agent, scope, project ? "GEMINI.md" : ".gemini/GEMINI.md");
            case CURSOR -> project
                    ? dedicated(agent, scope, ".cursor/rules/" + EXPORT_FILE_BASENAME + ".mdc",
                    "description: Axiomate agent memory (project rules and knowledge)\nglobs:\nalwaysApply: true")
                    : null; // Cursor user rules live in app settings, not on disk
            case ANTIGRAVITY -> project
                    ? dedicated(agent, scope, ".agents/rules/" + EXPORT_FILE_BASENAME + ".md",
                    "trigger: always_on\ndescription: Axiomate agent memory (project rules and knowledge)")
                    : managed(agent, scope, ".gemini/GEMINI.md");
            case WINDSURF -> project
                    ? dedicated(agent, scope, ".windsurf/rules/" + EXPORT_FILE_BASENAME + ".md", "trigger: always_on")
                    : managed(agent, scope, ".codeium/windsurf/memories/global_rules.md");
            case GITHUB_COPILOT -> project ? managed(agent, scope, ".github/copilot-instructions.md") : null;
            case CLINE -> dedicated(agent, scope, project
                    ? ".clinerules/" + EXPORT_FILE_BASENAME + ".md"
                    : "Documents/Cline/Rules/" + EXPORT_FILE_BASENAME + ".md", null);
            case ROO_CODE -> dedicated(agent, scope, ".roo/rules/" + EXPORT_FILE_BASENAME + ".md", null);
            case KIRO -> dedicated(agent, scope, ".kiro/steering/" + EXPORT_FILE_BASENAME + ".md", "inclusion: always");
        };
        return Optional.ofNullable(t);
    }

    /**
     * Claude Code stores per-project data under {@code ~/.claude/projects/<encoded>} where every
     * non-alphanumeric character of the absolute project path is replaced by '-'.
     */
    public static String encodeClaudeProjectDir(Path projectDir) {
        return projectDir.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
    }

    private static MemoryLocation file(CodingAgent a, MemoryScope s, String path, MemoryType t, String desc) {
        return new MemoryLocation(a, s, path, false, MD, t, true, null, desc);
    }

    private static MemoryLocation dir(CodingAgent a, MemoryScope s, String path, List<String> suffixes,
                                      MemoryType t, String indexFile, String desc) {
        return new MemoryLocation(a, s, path, true, suffixes, t, false, indexFile, desc);
    }

    private static ExportTarget managed(CodingAgent a, MemoryScope s, String path) {
        return new ExportTarget(a, s, path, ExportStyle.MANAGED_BLOCK, null);
    }

    private static ExportTarget dedicated(CodingAgent a, MemoryScope s, String path, String frontmatter) {
        return new ExportTarget(a, s, path, ExportStyle.DEDICATED_FILE, frontmatter);
    }
}

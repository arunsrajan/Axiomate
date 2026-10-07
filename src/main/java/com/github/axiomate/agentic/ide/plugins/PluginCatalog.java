package com.github.axiomate.agentic.ide.plugins;

import com.github.axiomate.agentic.ide.plugins.PluginManifest.CommandContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.McpServerContribution;
import com.github.axiomate.agentic.ide.plugins.PluginManifest.MemoryContribution;

import java.util.List;

/**
 * Built-in, curated plugin catalog shown in the Plugin Manager's marketplace tab.
 * MCP entries reference published MCP server packages; rule packs contribute memories and commands only.
 */
public final class PluginCatalog {

    public static final String CATEGORY_MCP = "MCP Servers";
    public static final String CATEGORY_RULES = "Rules & Knowledge";
    public static final String CATEGORY_WORKFLOW = "Workflows & Commands";

    private PluginCatalog() {
    }

    public static List<PluginManifest> builtIn() {
        return List.of(
                mcp("mcp-filesystem", "Filesystem", "Model Context Protocol",
                        "Secure read/write access to the project folder with configurable roots.",
                        McpServerContribution.stdio("filesystem", "Project filesystem access", "npx",
                                List.of("-y", "@modelcontextprotocol/server-filesystem", "${PROJECT_ROOT}"), List.of()),
                        "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem"),
                mcp("mcp-git", "Git", "Model Context Protocol",
                        "Read, search and manipulate the project's Git repository (log, diff, blame, commit).",
                        McpServerContribution.stdio("git", "Git repository tools", "uvx",
                                List.of("mcp-server-git", "--repository", "${PROJECT_ROOT}"), List.of()),
                        "https://github.com/modelcontextprotocol/servers/tree/main/src/git"),
                mcp("mcp-github", "GitHub", "GitHub",
                        "Issues, pull requests, code search and Actions via GitHub's official MCP server (requires Docker).",
                        McpServerContribution.stdio("github", "GitHub official MCP server", "docker",
                                List.of("run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "ghcr.io/github/github-mcp-server"),
                                List.of("GITHUB_PERSONAL_ACCESS_TOKEN")),
                        "https://github.com/github/github-mcp-server"),
                mcp("mcp-playwright", "Playwright Browser", "Microsoft",
                        "Drive a real browser: navigate, click, fill forms and snapshot pages for UI testing.",
                        McpServerContribution.stdio("playwright", "Browser automation", "npx",
                                List.of("-y", "@playwright/mcp@latest"), List.of()),
                        "https://github.com/microsoft/playwright-mcp"),
                mcp("mcp-context7", "Context7 Library Docs", "Upstash",
                        "Up-to-date, version-specific library documentation and code examples pulled into the prompt.",
                        McpServerContribution.stdio("context7", "Library documentation lookup", "npx",
                                List.of("-y", "@upstash/context7-mcp"), List.of()),
                        "https://github.com/upstash/context7"),
                mcp("mcp-fetch", "Web Fetch", "Model Context Protocol",
                        "Fetch web pages and convert them to Markdown for the agent to read.",
                        McpServerContribution.stdio("fetch", "Fetch URLs as Markdown", "uvx",
                                List.of("mcp-server-fetch"), List.of()),
                        "https://github.com/modelcontextprotocol/servers/tree/main/src/fetch"),
                mcp("mcp-memory-graph", "Knowledge Graph Memory", "Model Context Protocol",
                        "Persistent knowledge-graph memory (entities, relations, observations) shared across sessions.",
                        McpServerContribution.stdio("memory-graph", "Knowledge graph memory", "npx",
                                List.of("-y", "@modelcontextprotocol/server-memory"), List.of()),
                        "https://github.com/modelcontextprotocol/servers/tree/main/src/memory"),
                mcp("mcp-sequential-thinking", "Sequential Thinking", "Model Context Protocol",
                        "Structured step-by-step reasoning tool for decomposing complex problems.",
                        McpServerContribution.stdio("sequential-thinking", "Reflective problem solving", "npx",
                                List.of("-y", "@modelcontextprotocol/server-sequential-thinking"), List.of()),
                        "https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking"),
                mcp("mcp-time", "Time & Timezones", "Model Context Protocol",
                        "Current time and timezone conversions.",
                        McpServerContribution.stdio("time", "Time and timezone conversion", "uvx",
                                List.of("mcp-server-time"), List.of()),
                        "https://github.com/modelcontextprotocol/servers/tree/main/src/time"),

                new PluginManifest("rules-java-modern", "Modern Java 21+", "1.0.0",
                        "Idiomatic Java 21 conventions for the agent, plus a /modernize command.",
                        "Axiomate", CATEGORY_RULES, null, List.of("java", "style"), List.of(),
                        List.of(
                                rule("Java: data carriers", "Use records for immutable data carriers and DTOs. Prefer sealed interfaces with records for closed hierarchies and exhaustive switch pattern matching."),
                                rule("Java: control flow", "Prefer switch expressions and pattern matching for instanceof over if/else chains and casts. Use Optional only as a return type, never for fields or parameters."),
                                rule("Java: concurrency", "Prefer virtual threads (Executors.newVirtualThreadPerTaskExecutor) for blocking I/O workloads. Never block the Swing EDT; use SwingWorker or CompletableFuture and marshal UI updates with SwingUtilities.invokeLater."),
                                rule("Java: APIs", "Use java.time instead of Date/Calendar, java.nio.file.Files/Path instead of java.io.File for new code, and List.of/Map.of for immutable collections.")),
                        List.of(new CommandContribution("modernize", "Modernize code to Java 21 idioms",
                                "Modernize the following code to idiomatic Java 21 (records, sealed types, switch expressions, pattern matching, text blocks, java.time). Keep behaviour identical and explain each change briefly.\n\n$ARGUMENTS"))),

                new PluginManifest("rules-secure-coding", "Secure Coding (OWASP)", "1.0.0",
                        "OWASP-aligned security rules and a /security-review command.",
                        "Axiomate", CATEGORY_RULES, null, List.of("security", "owasp"), List.of(),
                        List.of(
                                rule("Security: input handling", "Treat all external input as untrusted. Validate against allow-lists, use parameterized queries (never string-concatenated SQL), and encode output for its context (HTML, shell, URL)."),
                                rule("Security: secrets", "Never hard-code or log secrets, tokens or passwords. Read them from environment variables or a secrets manager and redact them from error messages."),
                                rule("Security: dependencies & crypto", "Pin dependency versions and avoid libraries with known CVEs. Use vetted crypto primitives (e.g. AES-GCM, SHA-256+, Argon2/bcrypt for passwords); never invent crypto.")),
                        List.of(new CommandContribution("security-review", "Review code for security vulnerabilities",
                                "Perform a security review of $ARGUMENTS focusing on the OWASP Top 10: injection, broken access control, secrets exposure, insecure deserialization, SSRF and vulnerable dependencies. For each finding give severity, location, exploit scenario and a concrete fix."))),

                new PluginManifest("workflow-git-hygiene", "Commits & Pull Requests", "1.0.0",
                        "Conventional Commits rules plus /commit-message and /pr-description commands.",
                        "Axiomate", CATEGORY_WORKFLOW, null, List.of("git", "workflow"), List.of(),
                        List.of(rule("Git: commit messages", "Write commit messages in Conventional Commits format: type(scope): imperative summary under 72 characters (feat, fix, refactor, test, docs, chore), followed by a body explaining why.")),
                        List.of(
                                new CommandContribution("commit-message", "Draft a Conventional Commit message",
                                        "Draft a Conventional Commits message for these changes. Summary line under 72 characters, then a short body explaining the motivation.\n\n$ARGUMENTS"),
                                new CommandContribution("pr-description", "Write a pull request description",
                                        "Write a pull request description with sections: Summary, Changes, Testing, Risks. Be specific and concise.\n\n$ARGUMENTS"))),

                new PluginManifest("workflow-tdd", "Test-Driven Development", "1.0.0",
                        "Red-green-refactor workflow commands and testing rules.",
                        "Axiomate", CATEGORY_WORKFLOW, null, List.of("testing", "tdd"), List.of(),
                        List.of(rule("Testing: TDD discipline", "When adding behaviour, first write a failing test that captures the requirement, then the minimal code to pass it, then refactor with tests green. Cover edge cases: empty, null, boundaries and error paths.")),
                        List.of(
                                new CommandContribution("tdd", "Implement a feature test-first",
                                        "Implement the following using strict TDD. 1) Write failing tests and show them. 2) Write the minimal implementation. 3) Refactor. Feature: $ARGUMENTS"),
                                new CommandContribution("edge-cases", "List edge cases worth testing",
                                        "List the edge cases and failure modes worth testing for $ARGUMENTS, then write parameterized JUnit 5 tests for the most important ones."))),

                new PluginManifest("workflow-code-review", "Code Review Assistant", "1.0.0",
                        "Structured /review and /explain-diff commands for reviewing changes.",
                        "Axiomate", CATEGORY_WORKFLOW, null, List.of("review"), List.of(), List.of(),
                        List.of(
                                new CommandContribution("review", "Review code for bugs and clarity",
                                        "Review $ARGUMENTS as a senior engineer. Report correctness bugs first (with a concrete failing scenario each), then risky edge cases, then readability issues. Skip style nits unless they hide bugs."),
                                new CommandContribution("explain-diff", "Explain a diff in plain language",
                                        "Explain what this change does behaviourally, why it might have been made, and what could break:\n\n$ARGUMENTS")))
        );
    }

    private static PluginManifest mcp(String id, String name, String author, String description,
                                      McpServerContribution server, String homepage) {
        return new PluginManifest(id, name, "1.0.0", description, author, CATEGORY_MCP, homepage,
                List.of("mcp"), List.of(server), List.of(), List.of());
    }

    private static MemoryContribution rule(String title, String content) {
        return new MemoryContribution(title, content, "PROJECT_RULE", List.of());
    }
}

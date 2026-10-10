package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import com.github.axiomate.agentic.ide.util.WorkspaceSearch;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;

/**
 * Lets the agent find code by content (like grep) instead of listing folders and reading files one by one.
 */
public class SearchFilesTool implements AgentTool {

    static final int DEFAULT_RESULTS = 100;
    static final int MAX_RESULTS = 500;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public String getDescription() {
        return """
            search_files: Search the project's files for text or a regular expression and get matching lines as
            path:line: text. Use it to find where something is defined or used before reading files.
            Build output, dependency and VCS folders and binary files are skipped.
            Arguments JSON schema:
            {
              "query": "text or regex to find (required)",
              "regex": false,
              "case_sensitive": false,
              "whole_word": false,
              "include": "optional globs, e.g. \\"*.java\\" or \\"src/**/*.ts, *.md\\"",
              "path": "optional sub-folder of the project to search",
              "max_results": 100
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        String trimmed = arguments == null ? "" : arguments.trim();
        JsonNode json = trimmed.startsWith("{") ? mapper.readTree(trimmed) : mapper.createObjectNode().put("query", trimmed);
        String query = json.path("query").asText("");
        if (query.isEmpty()) return "ERROR: Provide the text to find, e.g. {\"query\": \"class UserService\"}";

        File project = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (project == null) return "ERROR: No project folder is open.";
        Path projectRoot = project.getCanonicalFile().toPath();
        Path root = projectRoot;
        String sub = json.path("path").asText("").trim();
        if (!sub.isEmpty() && !sub.equals(".")) {
            root = projectRoot.resolve(sub).normalize();
            if (!root.toFile().getCanonicalFile().toPath().startsWith(projectRoot)) {
                return "ERROR: 'path' must be inside the project: " + sub;
            }
            if (!root.toFile().isDirectory()) return "ERROR: Folder not found in the project: " + sub;
        }

        int max = Math.max(1, Math.min(MAX_RESULTS, json.path("max_results").asInt(DEFAULT_RESULTS)));
        WorkspaceSearch.Options options = new WorkspaceSearch.Options(query, json.path("regex").asBoolean(false),
                json.path("case_sensitive").asBoolean(false), json.path("whole_word").asBoolean(false),
                json.path("include").asText(""), max);
        WorkspaceSearch.Result result;
        try {
            result = WorkspaceSearch.search(root, options, () -> Thread.currentThread().isInterrupted());
        } catch (PatternSyntaxException e) {
            return "ERROR: Invalid regular expression: " + e.getDescription()
                    + ". Escape special characters, or pass \"regex\": false to search for the text literally.";
        }

        String prefix = root.equals(projectRoot) ? "" : projectRoot.relativize(root).toString().replace('\\', '/') + "/";
        if (result.matches().isEmpty()) {
            return "No matches for \"" + query + "\" in " + result.filesSearched() + " files"
                    + (options.include().isBlank() ? "" : " matching " + options.include()) + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d matching line(s) in %d file(s) for \"%s\" (%d files searched):%n",
                result.matches().size(), result.filesMatched(), query, result.filesSearched()));
        for (WorkspaceSearch.Match m : result.matches()) {
            sb.append(prefix).append(m.relativePath()).append(':').append(m.line()).append(": ")
                    .append(m.lineText().strip()).append('\n');
        }
        if (result.truncated()) {
            sb.append("[Stopped at ").append(max).append(" results. Narrow the search with \"include\" or \"path\".]\n");
        }
        return sb.toString().stripTrailing();
    }
}

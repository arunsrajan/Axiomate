package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Autonomous Code Editing Tool supporting precision operations:
 * - replace_lines: replace line range [startLine, endLine]
 * - insert_at_line: insert code at line number
 * - replace_content: targeted snippet search and replace
 * - write_file: create or overwrite file with full content
 * - read_file: read file with line numbers
 * - delete_lines: remove line range
 * Automatically syncs modifications to the live IDE editor tabs.
 */
public class AutonomousCodeEditorTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(AutonomousCodeEditorTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "code_editor";
    }

    @Override
    public String getDescription() {
        return """
            code_editor: Precision autonomous code editing and inspection tool.
            Supported actions:
            1. 'replace_lines':
               { "action": "replace_lines", "filePath": "src/App.java", "startLine": 10, "endLine": 15, "replacement": "new code" }
            2. 'insert_at_line':
               { "action": "insert_at_line", "filePath": "src/App.java", "line": 20, "content": "code to insert" }
            3. 'replace_content':
               { "action": "replace_content", "filePath": "src/App.java", "target": "old snippet", "replacement": "new snippet" }
            4. 'write_file':
               { "action": "write_file", "filePath": "src/App.java", "content": "full content" }
            5. 'delete_lines':
               { "action": "delete_lines", "filePath": "src/App.java", "startLine": 10, "endLine": 12 }
            6. 'read_file':
               { "action": "read_file", "filePath": "src/App.java", "startLine": 1, "endLine": 50 }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode json = mapper.readTree(arguments);
        String action = json.path("action").asText("replace_content").toLowerCase();
        String filePath = json.path("filePath").asText("");

        File targetFile = resolveTargetFile(filePath);
        if (targetFile == null) {
            return "ERROR: Could not resolve target file. Provide a valid 'filePath' or open a file in the editor.";
        }

        return switch (action) {
            case "read_file" -> handleReadFile(targetFile, json);
            case "replace_lines" -> handleReplaceLines(targetFile, json);
            case "insert_at_line" -> handleInsertAtLine(targetFile, json);
            case "replace_content" -> handleReplaceContent(targetFile, json);
            case "write_file" -> handleWriteFile(targetFile, json);
            case "delete_lines" -> handleDeleteLines(targetFile, json);
            default -> "ERROR: Unknown action '" + action + "'. Valid actions: replace_lines, insert_at_line, replace_content, write_file, delete_lines, read_file.";
        };
    }

    private File resolveTargetFile(String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            File baseDir = ProjectManager.getInstance().getCurrentProjectDirectory();
            Path path = baseDir.toPath().resolve(filePath).normalize();
            return path.toFile();
        }
        return ProjectManager.getInstance().getActiveFile();
    }

    private String handleReadFile(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        List<String> lines = Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8);
        int startLine = json.path("startLine").asInt(1);
        int endLine = json.path("endLine").asInt(lines.size());

        startLine = Math.max(1, startLine);
        endLine = Math.min(lines.size(), Math.max(startLine, endLine));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("File: %s (Lines %d-%d of %d):\n", targetFile.getName(), startLine, endLine, lines.size()));
        for (int i = startLine; i <= endLine; i++) {
            sb.append(String.format("%4d: %s\n", i, lines.get(i - 1)));
        }
        return sb.toString();
    }

    private String handleReplaceLines(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8));
        int startLine = json.path("startLine").asInt(-1);
        int endLine = json.path("endLine").asInt(-1);
        String replacement = json.path("replacement").asText("");

        if (startLine < 1 || endLine < startLine || startLine > lines.size()) {
            return String.format("ERROR: Invalid line range [%d, %d] for file with %d lines.", startLine, endLine, lines.size());
        }

        endLine = Math.min(lines.size(), endLine);

        // Remove old range
        for (int i = 0; i <= (endLine - startLine); i++) {
            lines.remove(startLine - 1);
        }

        // Insert new lines
        String[] newLines = replacement.split("\r?\n", -1);
        for (int i = newLines.length - 1; i >= 0; i--) {
            lines.add(startLine - 1, newLines[i]);
        }

        String updated = String.join("\n", lines);
        saveAndNotify(targetFile, updated);

        return String.format("SUCCESS: Replaced lines %d-%d in %s with %d lines of new code.",
                startLine, endLine, targetFile.getName(), newLines.length);
    }

    private String handleInsertAtLine(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8));
        int line = json.path("line").asInt(lines.size() + 1);
        String content = json.path("content").asText("");

        int insertIndex = Math.max(0, Math.min(lines.size(), line - 1));
        String[] newLines = content.split("\r?\n", -1);

        for (int i = newLines.length - 1; i >= 0; i--) {
            lines.add(insertIndex, newLines[i]);
        }

        String updated = String.join("\n", lines);
        saveAndNotify(targetFile, updated);

        return String.format("SUCCESS: Inserted %d lines at line %d in %s.",
                newLines.length, line, targetFile.getName());
    }

    private String handleReplaceContent(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        String target = json.path("target").asText("");
        String replacement = json.path("replacement").asText("");

        if (target.isEmpty()) {
            return "ERROR: 'target' snippet cannot be empty for replace_content action.";
        }

        String existing = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);
        if (!existing.contains(target)) {
            return "ERROR: 'target' snippet not found in " + targetFile.getName();
        }

        String updated = existing.replace(target, replacement);
        saveAndNotify(targetFile, updated);

        return String.format("SUCCESS: Replaced target snippet in %s (Modified %d chars).",
                targetFile.getName(), updated.length());
    }

    private String handleWriteFile(File targetFile, JsonNode json) throws IOException {
        String content = json.path("content").asText("");
        if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
            targetFile.getParentFile().mkdirs();
        }

        boolean created = !targetFile.exists();
        saveAndNotify(targetFile, content);

        return String.format("SUCCESS: %s file %s (%d characters).",
                created ? "Created" : "Overwrote", targetFile.getName(), content.length());
    }

    private String handleDeleteLines(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8));
        int startLine = json.path("startLine").asInt(-1);
        int endLine = json.path("endLine").asInt(-1);

        if (startLine < 1 || endLine < startLine || startLine > lines.size()) {
            return String.format("ERROR: Invalid line range [%d, %d] for file with %d lines.", startLine, endLine, lines.size());
        }

        endLine = Math.min(lines.size(), endLine);
        int count = endLine - startLine + 1;

        for (int i = 0; i < count; i++) {
            lines.remove(startLine - 1);
        }

        String updated = String.join("\n", lines);
        saveAndNotify(targetFile, updated);

        return String.format("SUCCESS: Deleted %d lines (%d-%d) in %s.", count, startLine, endLine, targetFile.getName());
    }

    private void saveAndNotify(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        log.info("Saved file {}: {} characters", file.getAbsolutePath(), content.length());
        ProjectManager.getInstance().notifyFileModified(file, content);
    }
}


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
            3. 'replace_content' (target must appear exactly once unless "replace_all": true):
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

        // Feature 31: Sandboxed Execution path validation
        var sandboxCheck = com.github.axiomate.agentic.ide.features.security.ExecutionSandbox.getInstance().validatePathAccess(targetFile);
        if (!sandboxCheck.allowed()) {
            return "ERROR: " + sandboxCheck.violationReason();
        }

        // Feature 45: Policy-as-Code enforcement
        var policyCheck = com.github.axiomate.agentic.ide.features.extensibility.PolicyAsCodeEngine.getInstance().evaluate(targetFile.getPath(), action);
        if (!policyCheck.allowed()) {
            return "ERROR: " + policyCheck.message();
        }

        // Feature 14: Human-in-the-loop breakpoints
        if (com.github.axiomate.agentic.ide.features.execution.HumanInTheLoopGate.getInstance().shouldPauseForApproval(targetFile.getPath(), action)) {
            return "BLOCKED: Human-in-the-loop breakpoint triggered for " + targetFile.getName() + " [" + action + "]. Approval required.";
        }

        // Feature 35: Irreversible-action gate
        if (!com.github.axiomate.agentic.ide.features.security.IrreversibleActionGate.getInstance().checkAndConfirm(action, targetFile.getPath())) {
            return "BLOCKED: Irreversible action " + action + " requires explicit confirmation.";
        }

        // Feature 36: Full audit trail logging
        com.github.axiomate.agentic.ide.features.security.AuditTrailService.getInstance()
                .recordEvent("AGENT", "CODE_EDITOR", targetFile.getPath(), "Action: " + action);

        try {
            return dispatch(action, targetFile, json);
        } catch (NotUtf8Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String dispatch(String action, File targetFile, JsonNode json) throws IOException {
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

    /**
     * A text file split into lines, remembering its line separator and whether it ends with one, so line edits
     * write the file back exactly as it was apart from the edited lines.
     */
    record TextLines(List<String> lines, String separator, boolean trailingNewline) {

        static TextLines parse(String content) {
            String sep = content.contains("\r\n") ? "\r\n" : "\n";
            boolean trailing = content.endsWith("\n");
            String body = trailing ? content.substring(0, content.length() - (content.endsWith("\r\n") ? 2 : 1)) : content;
            List<String> lines = new ArrayList<>(content.isEmpty() ? List.of() : List.of(body.split("\r?\n", -1)));
            return new TextLines(lines, sep, trailing);
        }

        String join() {
            if (lines.isEmpty()) return "";
            return String.join(separator, lines) + (trailingNewline ? separator : "");
        }
    }

    static final class NotUtf8Exception extends IOException {
        NotUtf8Exception(String message) {
            super(message);
        }
    }

    /** Reads a file that is about to be edited; refuses non-UTF-8 files rather than corrupting them. */
    private static String readForEdit(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new NotUtf8Exception(file.getName() + " is not UTF-8 text, so it is not edited here (it would be corrupted). "
                    + "Use the shell to change it, or convert it to UTF-8 first.");
        }
    }

    /** Lines of new code from the model; one trailing newline is not an extra empty line. */
    private static String[] newLines(String text) {
        String t = text.endsWith("\r\n") ? text.substring(0, text.length() - 2)
                : text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return t.split("\r?\n", -1);
    }

    /** Credentials the agent is about to write are masked; text already in the file is left alone. */
    private static String sanitizeNew(String text) {
        return com.github.axiomate.agentic.ide.features.security.SecretLeakGuard.getInstance().scanAndSanitize(text).sanitizedText();
    }

    private String handleReadFile(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        // Lenient decoding for reading: a stray non-UTF-8 byte must not make the whole file unreadable
        String content = new String(Files.readAllBytes(targetFile.toPath()), StandardCharsets.UTF_8);
        List<String> lines = TextLines.parse(content).lines();
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

        TextLines text = TextLines.parse(readForEdit(targetFile));
        List<String> lines = text.lines();
        int startLine = json.path("startLine").asInt(-1);
        int endLine = json.path("endLine").asInt(-1);
        String replacement = sanitizeNew(json.path("replacement").asText(""));

        if (startLine < 1 || endLine < startLine || startLine > lines.size()) {
            return String.format("ERROR: Invalid line range [%d, %d] for file with %d lines.", startLine, endLine, lines.size());
        }

        endLine = Math.min(lines.size(), endLine);
        lines.subList(startLine - 1, endLine).clear();
        String[] added = replacement.isEmpty() ? new String[0] : newLines(replacement);
        lines.addAll(startLine - 1, List.of(added));

        saveAndNotify(targetFile, text.join());
        return String.format("SUCCESS: Replaced lines %d-%d in %s with %d lines of new code.",
                startLine, endLine, targetFile.getName(), added.length);
    }

    private String handleInsertAtLine(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        TextLines text = TextLines.parse(readForEdit(targetFile));
        List<String> lines = text.lines();
        int line = json.path("line").asInt(lines.size() + 1);
        String content = sanitizeNew(json.path("content").asText(""));

        int insertIndex = Math.max(0, Math.min(lines.size(), line - 1));
        String[] added = newLines(content);
        lines.addAll(insertIndex, List.of(added));
        if (lines.size() == added.length && !content.isEmpty()) {
            text = new TextLines(lines, text.separator(), true); // a new file's first lines end with a newline
        }

        saveAndNotify(targetFile, text.join());
        return String.format("SUCCESS: Inserted %d lines at line %d in %s.",
                added.length, insertIndex + 1, targetFile.getName());
    }

    private String handleReplaceContent(File targetFile, JsonNode json) throws IOException {
        if (!targetFile.exists()) {
            return "ERROR: File not found: " + targetFile.getAbsolutePath();
        }

        String target = json.path("target").asText("");
        String replacement = sanitizeNew(json.path("replacement").asText(""));
        boolean replaceAll = json.path("replace_all").asBoolean(false);

        if (target.isEmpty()) {
            return "ERROR: 'target' snippet cannot be empty for replace_content action.";
        }

        String existing = readForEdit(targetFile);
        if (existing.contains("\r\n")) {
            // Models write "\n"; match and write in the file's own line endings
            target = target.replace("\r\n", "\n").replace("\n", "\r\n");
            replacement = replacement.replace("\r\n", "\n").replace("\n", "\r\n");
        }
        int count = countOccurrences(existing, target);
        if (count == 0) {
            return "ERROR: 'target' snippet not found in " + targetFile.getName()
                    + ". Read the file again and copy the snippet exactly, including indentation.";
        }
        if (count > 1 && !replaceAll) {
            return "ERROR: 'target' snippet appears " + count + " times in " + targetFile.getName()
                    + ". Include more surrounding lines so it is unique, or pass \"replace_all\": true to change every occurrence.";
        }

        String updated = replaceAll ? existing.replace(target, replacement)
                : existing.substring(0, existing.indexOf(target)) + replacement
                  + existing.substring(existing.indexOf(target) + target.length());
        saveAndNotify(targetFile, updated);

        return String.format("SUCCESS: Replaced target snippet in %s (%d occurrence(s)).",
                targetFile.getName(), replaceAll ? count : 1);
    }

    static int countOccurrences(String text, String snippet) {
        int count = 0;
        for (int i = text.indexOf(snippet); i >= 0; i = text.indexOf(snippet, i + snippet.length())) count++;
        return count;
    }

    private String handleWriteFile(File targetFile, JsonNode json) throws IOException {
        String content = sanitizeNew(json.path("content").asText(""));
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

        TextLines text = TextLines.parse(readForEdit(targetFile));
        List<String> lines = text.lines();
        int startLine = json.path("startLine").asInt(-1);
        int endLine = json.path("endLine").asInt(-1);

        if (startLine < 1 || endLine < startLine || startLine > lines.size()) {
            return String.format("ERROR: Invalid line range [%d, %d] for file with %d lines.", startLine, endLine, lines.size());
        }

        endLine = Math.min(lines.size(), endLine);
        int count = endLine - startLine + 1;
        lines.subList(startLine - 1, endLine).clear();

        saveAndNotify(targetFile, text.join());
        return String.format("SUCCESS: Deleted %d lines (%d-%d) in %s.", count, startLine, endLine, targetFile.getName());
    }

    private void saveAndNotify(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        log.info("Saved file {}: {} characters", file.getAbsolutePath(), content.length());
        ProjectManager.getInstance().notifyFileModified(file, content);
    }
}


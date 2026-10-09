package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;

/**
 * Tool allowing the agent to refactor, patch, or replace code in a project file.
 */
public class CodeRefactorTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CodeRefactorTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "code_refactor";
    }

    @Override
    public String getDescription() {
        return """
            code_refactor: Apply refactoring, updates, or replacement to a source file.
            Arguments JSON schema:
            {
              "filePath": "path/to/File.java",
              "targetCode": "original snippet to replace (optional - if omitted, whole file is updated)",
              "replacementCode": "new refactored code snippet or whole file"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode json = mapper.readTree(arguments);
        String filePath = json.path("filePath").asText("");
        String targetCode = json.path("targetCode").asText("");
        String replacementCode = json.path("replacementCode").asText("");

        File targetFile;
        if (filePath.isBlank()) {
            targetFile = ProjectManager.getInstance().getActiveFile();
            if (targetFile == null) {
                return "ERROR: No active file currently open in editor and no filePath specified.";
            }
        } else {
            File baseDir = ProjectManager.getInstance().getCurrentProjectDirectory();
            Path p = baseDir.toPath().resolve(filePath).normalize();
            targetFile = p.toFile();
        }

        if (!targetFile.exists()) {
            return "ERROR: Target file does not exist: " + targetFile.getAbsolutePath();
        }
        if (targetCode.isBlank() && replacementCode.isBlank()) {
            return "ERROR: Provide replacementCode (and targetCode to change part of the file). Nothing was changed.";
        }

        // Same rules and safeguards as code_editor: project-only paths, unique snippet match, the file's own line
        // endings, no credentials written, open editor tabs updated
        com.fasterxml.jackson.databind.node.ObjectNode edit = mapper.createObjectNode();
        edit.put("filePath", targetFile.getAbsolutePath());
        if (targetCode.isBlank()) {
            edit.put("action", "write_file");
            edit.put("content", replacementCode);
        } else {
            edit.put("action", "replace_content");
            edit.put("target", targetCode);
            edit.put("replacement", replacementCode);
        }
        String result = new AutonomousCodeEditorTool().execute(edit.toString());
        if (result.startsWith("SUCCESS")) log.info("Refactored file {}", targetFile.getAbsolutePath());
        return result;
    }
}


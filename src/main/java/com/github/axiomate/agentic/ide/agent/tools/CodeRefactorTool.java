package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
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

        String existing = Files.readString(targetFile.toPath());
        String updated;
        if (targetCode.isBlank()) {
            updated = replacementCode;
        } else {
            if (!existing.contains(targetCode)) {
                return "ERROR: targetCode snippet not found in " + targetFile.getName();
            }
            updated = existing.replace(targetCode, replacementCode);
        }

        Files.writeString(targetFile.toPath(), updated);
        log.info("Refactored file {}", targetFile.getAbsolutePath());
        return "SUCCESS: Successfully refactored " + targetFile.getName() + " (" + updated.length() + " chars)";
    }
}


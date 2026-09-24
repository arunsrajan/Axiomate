package com.github.agentforge.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.agentforge.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tool for the AI Agent to inspect, list, read, and write project files.
 */
public class FileSystemTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(FileSystemTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "file_system";
    }

    @Override
    public String getDescription() {
        return """
            file_system: Read, write, or list files in the current workspace.
            Arguments JSON schema:
            {
              "action": "read" | "write" | "list",
              "path": "relative/or/absolute/path",
              "content": "file content to write (only for write action)"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        JsonNode json = mapper.readTree(arguments);
        String action = json.path("action").asText("list").toLowerCase();
        String pathStr = json.path("path").asText("");

        File baseDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        Path targetPath;
        if (pathStr == null || pathStr.isBlank()) {
            targetPath = baseDir.toPath();
        } else {
            Path p = Paths.get(pathStr);
            if (p.isAbsolute()) {
                targetPath = p;
            } else {
                targetPath = baseDir.toPath().resolve(p).normalize();
            }
        }

        return switch (action) {
            case "read" -> readFile(targetPath);
            case "write" -> {
                String content = json.path("content").asText("");
                yield writeFile(targetPath, content);
            }
            case "list" -> listFiles(targetPath);
            default -> throw new IllegalArgumentException("Unknown file_system action: " + action);
        };
    }

    private String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "ERROR: File not found: " + path.toAbsolutePath();
        }
        if (Files.isDirectory(path)) {
            return "ERROR: Path is a directory: " + path.toAbsolutePath();
        }
        return Files.readString(path);
    }

    private String writeFile(Path path, String content) throws IOException {
        if (path.getParent() != null && !Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
        log.info("AI Agent wrote {} bytes to {}", content.length(), path);
        return "SUCCESS: Wrote " + content.length() + " characters to " + path.toAbsolutePath();
    }

    private String listFiles(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "ERROR: Directory not found: " + path.toAbsolutePath();
        }
        try (Stream<Path> stream = Files.list(path)) {
            String files = stream.map(p -> (Files.isDirectory(p) ? "[DIR] " : "[FILE] ") + p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return "Directory contents of " + path.toAbsolutePath() + ":\n" + (files.isEmpty() ? "(empty)" : files);
        }
    }
}

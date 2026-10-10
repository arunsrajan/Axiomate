package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
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
        if (com.github.axiomate.agentic.ide.agent.vision.VisionSupport.isImageFile(path.toFile())) {
            return "This is an image file. Use the view_image tool to look at it: " + path.toAbsolutePath();
        }
        long size = Files.size(path);
        byte[] bytes;
        try (var in = Files.newInputStream(path)) {
            bytes = in.readNBytes((int) Math.min(size, MAX_READ_BYTES));
        }
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++) {
            if (bytes[i] == 0) {
                return "ERROR: " + path.getFileName() + " is a binary file (" + size + " bytes) and cannot be shown as text.";
            }
        }
        // Lenient decoding: a stray non-UTF-8 byte must not make the whole file unreadable
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (size > MAX_READ_BYTES) {
            text += "\n\n[TRUNCATED: showing the first " + MAX_READ_BYTES + " of " + size + " bytes. "
                    + "Use code_editor read_file with startLine/endLine, or the shell, for the rest.]";
        }
        return text;
    }

    /** Larger files are cut so one read cannot flood the conversation. */
    static final int MAX_READ_BYTES = 200_000;

    private String writeFile(Path path, String content) throws IOException {
        // Same rules as code_editor: stay inside the project and never write credentials
        var sandbox = com.github.axiomate.agentic.ide.features.security.ExecutionSandbox.getInstance().validatePathAccess(path.toFile());
        if (!sandbox.allowed()) {
            return "ERROR: " + sandbox.violationReason();
        }
        content = com.github.axiomate.agentic.ide.features.security.SecretLeakGuard.getInstance().scanAndSanitize(content).sanitizedText();
        if (path.getParent() != null && !Files.exists(path.getParent())) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, java.nio.charset.StandardCharsets.UTF_8);
        log.info("AI Agent wrote {} bytes to {}", content.length(), path);
        // Open editor tabs show the new text instead of keeping (and later saving over it with) the old one
        ProjectManager.getInstance().notifyFileModified(path.toFile(), content);
        return "SUCCESS: Wrote " + content.length() + " characters to " + path.toAbsolutePath();
    }

    private String listFiles(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "ERROR: Directory not found: " + path.toAbsolutePath();
        }
        if (!Files.isDirectory(path)) {
            return "ERROR: " + path.toAbsolutePath() + " is a file, not a directory. Use the read action to see its contents.";
        }
        try (Stream<Path> stream = Files.list(path)) {
            String files = stream.map(p -> (Files.isDirectory(p) ? "[DIR] " : "[FILE] ") + p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return "Directory contents of " + path.toAbsolutePath() + ":\n" + (files.isEmpty() ? "(empty)" : files);
        }
    }
}


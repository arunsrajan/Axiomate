package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Tool allowing the AI Agent to execute Bash commands and shell scripts.
 * Automatically discovers Bash on Windows (Git Bash, WSL, MSYS2) and Unix environments.
 */
public class BashTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return """
            bash: Execute bash commands or scripts in the project working directory.
            Preferred command execution tool on Linux, Unix, and macOS environments.
            Commands time out after 120 seconds unless "timeout_seconds" (max 900) is given; stdin is closed.
            Arguments JSON schema:
            {
              "command": "bash command or script (e.g. ls -la, git status, ./gradlew build, mvn test)"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        String command;
        JsonNode json = null;
        if (arguments.trim().startsWith("{")) {
            json = mapper.readTree(arguments);
            command = json.path("command").asText();
            if (command.isBlank() && json.has("script")) {
                command = json.path("script").asText();
            }
        } else {
            command = arguments.trim();
        }

        if (command == null || command.isBlank()) {
            return "ERROR: Empty bash command provided.";
        }

        File workingDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        String blocked = ProcessRunner.guard("bash", command, workingDir);
        if (blocked != null) return blocked;

        String bashExecutable = findBashExecutable();
        log.info("Executing bash command using [{}]: '{}'", bashExecutable, command);
        int timeout = ProcessRunner.timeoutFrom(json);
        ProcessRunner.Result result;
        try {
            result = ProcessRunner.run(List.of(bashExecutable, "-c", command), workingDir, timeout, StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            if ("bash".equals(bashExecutable)) {
                return "ERROR: bash is not available on this machine (" + ex.getMessage() + ").";
            }
            log.warn("Failed to start with {}, attempting fallback to 'bash' in PATH", bashExecutable, ex);
            try {
                result = ProcessRunner.run(List.of("bash", "-c", command), workingDir, timeout, StandardCharsets.UTF_8);
            } catch (java.io.IOException again) {
                return "ERROR: bash is not available on this machine (" + again.getMessage() + ").";
            }
        }
        return result.describe("Bash command");
    }

    public static String findBashExecutable() {
        boolean isWindows = com.github.axiomate.agentic.ide.util.OSUtils.isWindows();
        if (!isWindows) {
            return "bash";
        }

        // Common Git Bash locations on Windows
        List<String> candidatePaths = List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                "C:\\Users\\" + System.getProperty("user.name") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe",
                "C:\\msys64\\usr\\bin\\bash.exe"
        );

        for (String p : candidatePaths) {
            File f = new File(p);
            if (f.exists() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }

        return "bash.exe";
    }
}


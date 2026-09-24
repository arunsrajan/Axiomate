package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
            Arguments JSON schema:
            {
              "command": "bash command or script (e.g. ls -la, git status, ./gradlew build, mvn test)"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        String command;
        if (arguments.trim().startsWith("{")) {
            JsonNode json = mapper.readTree(arguments);
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
        String bashExecutable = findBashExecutable();

        log.info("Executing bash command using [{}]: '{}'", bashExecutable, command);

        List<String> commandList = new ArrayList<>();
        commandList.add(bashExecutable);
        commandList.add("-c");
        commandList.add(command);

        ProcessBuilder pb = new ProcessBuilder(commandList);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (Exception ex) {
            log.warn("Failed to start with {}, attempting fallback to 'bash' in PATH", bashExecutable, ex);
            pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);
            process = pb.start();
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (output.length() > 25000) {
                    output.append("\n[OUTPUT TRUNCATED]");
                    break;
                }
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "ERROR: Bash command timed out after 60 seconds.\nPartial output:\n" + output;
        }

        int exitCode = process.exitValue();
        return "Exit code: " + exitCode + "\nOutput:\n" + (output.isEmpty() ? "(No output)" : output.toString());
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


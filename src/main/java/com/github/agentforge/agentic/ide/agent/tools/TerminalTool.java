package com.github.agentforge.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.agentforge.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Tool allowing the AI Agent to execute shell / terminal commands in the project directory.
 */
public class TerminalTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(TerminalTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "terminal";
    }

    @Override
    public String getDescription() {
        return """
            terminal: Execute a shell command in the project root directory.
            Arguments JSON schema:
            {
              "command": "command to run (e.g. dir, mvn test, javac HelloWorld.java, git status)"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        String command;
        if (arguments.trim().startsWith("{")) {
            JsonNode json = mapper.readTree(arguments);
            command = json.path("command").asText();
        } else {
            command = arguments.trim();
        }

        if (command == null || command.isBlank()) {
            return "ERROR: Empty command provided";
        }

        File workingDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        log.info("Agent executing command: '{}' in {}", command, workingDir);

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb;
        if (isWindows) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("bash", "-c", command);
        }

        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (output.length() > 20000) {
                    output.append("\n[OUTPUT TRUNCATED]");
                    break;
                }
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "ERROR: Command timed out after 30 seconds.\nPartial output:\n" + output;
        }

        int exitCode = process.exitValue();
        return "Exit code: " + exitCode + "\nOutput:\n" + (output.isEmpty() ? "(No output)" : output.toString());
    }
}

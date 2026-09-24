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
 * Tool allowing the AI Agent to execute shell / terminal commands in the project directory,
 * with explicit support for PowerShell, Bash, and default OS shell.
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
            Automatically selects PowerShell on Windows and Bash on Linux/macOS.
            Arguments JSON schema:
            {
              "command": "command to run (e.g. dir, mvn test, javac HelloWorld.java, git status)",
              "shell": "optional shell choice: 'powershell', 'bash', 'cmd', or 'default'"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        String command;
        String shell = "default";

        if (arguments.trim().startsWith("{")) {
            JsonNode json = mapper.readTree(arguments);
            command = json.path("command").asText();
            if (json.has("shell")) {
                shell = json.path("shell").asText("default").toLowerCase();
            }
        } else {
            command = arguments.trim();
        }

        if (command == null || command.isBlank()) {
            return "ERROR: Empty command provided";
        }

        File workingDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        log.info("Agent executing terminal command [{}] in {}: '{}'", shell, workingDir, command);

        List<String> commandList = new ArrayList<>();
        boolean isWindows = com.github.axiomate.agentic.ide.util.OSUtils.isWindows();

        if ("powershell".equals(shell) || ("default".equals(shell) && isWindows)) {
            commandList.add(PowerShellTool.findPowerShellExecutable());
            commandList.add("-NoProfile");
            commandList.add("-NonInteractive");
            commandList.add("-ExecutionPolicy");
            commandList.add("Bypass");
            commandList.add("-Command");
            commandList.add(command);
        } else if ("bash".equals(shell) || ("default".equals(shell) && !isWindows)) {
            commandList.add(BashTool.findBashExecutable());
            commandList.add("-c");
            commandList.add(command);
        } else if ("cmd".equals(shell)) {
            commandList.add("cmd.exe");
            commandList.add("/c");
            commandList.add(command);
        } else {
            if (isWindows) {
                commandList.add(PowerShellTool.findPowerShellExecutable());
                commandList.add("-Command");
                commandList.add(command);
            } else {
                commandList.add("bash");
                commandList.add("-c");
                commandList.add(command);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(commandList);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
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
            return "ERROR: Command timed out after 60 seconds.\nPartial output:\n" + output;
        }

        int exitCode = process.exitValue();
        return "Exit code: " + exitCode + "\nOutput:\n" + (output.isEmpty() ? "(No output)" : output.toString());
    }
}


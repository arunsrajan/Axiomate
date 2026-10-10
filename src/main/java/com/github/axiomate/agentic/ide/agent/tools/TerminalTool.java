package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
            Commands time out after 120 seconds unless "timeout_seconds" (max 900) is given; stdin is closed.
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
        JsonNode json = null;

        if (arguments.trim().startsWith("{")) {
            json = mapper.readTree(arguments);
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

        // Feature 31: Sandboxed Execution command validation
        var sandboxCheck = com.github.axiomate.agentic.ide.features.security.ExecutionSandbox.getInstance().validateCommand(command);
        if (!sandboxCheck.allowed()) {
            return "ERROR: " + sandboxCheck.violationReason();
        }

        // Feature 35 + 36: confirmation for destructive commands, audit trail
        String blocked = ProcessRunner.guard("terminal", command, workingDir);
        if (blocked != null) return blocked;

        log.info("Agent executing terminal command [{}] in {}: '{}'", shell, workingDir, command);

        List<String> commandList = new ArrayList<>();
        boolean isWindows = com.github.axiomate.agentic.ide.util.OSUtils.isWindows();
        boolean windowsShell;
        if ("powershell".equals(shell) || (!"bash".equals(shell) && !"cmd".equals(shell) && isWindows)) {
            commandList.addAll(List.of(PowerShellTool.findPowerShellExecutable(), "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", command));
            windowsShell = true;
        } else if ("cmd".equals(shell)) {
            commandList.addAll(List.of("cmd.exe", "/c", command));
            windowsShell = true;
        } else {
            commandList.addAll(List.of(BashTool.findBashExecutable(), "-c", command));
            windowsShell = false;
        }

        try {
            ProcessRunner.Result result = ProcessRunner.run(commandList, workingDir, ProcessRunner.timeoutFrom(json),
                    windowsShell ? PowerShellTool.nativeCharset() : StandardCharsets.UTF_8);
            return result.describe("Command");
        } catch (java.io.IOException e) {
            return "ERROR: Could not start " + commandList.get(0) + ": " + e.getMessage();
        }
    }
}


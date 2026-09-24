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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tool allowing the AI Agent to execute PowerShell commands and scripts.
 * Supports Windows PowerShell and cross-platform PowerShell Core (pwsh).
 */
public class PowerShellTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(PowerShellTool.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "powershell";
    }

    @Override
    public String getDescription() {
        return """
            powershell: Execute PowerShell commands, scripts, and cmdlets in the project directory.
            Arguments JSON schema:
            {
              "command": "PowerShell command or script (e.g. Get-ChildItem, Select-String, Test-Path, mvn test)"
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
            return "ERROR: Empty powershell command provided.";
        }

        File workingDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        String psExecutable = findPowerShellExecutable();

        log.info("Executing PowerShell using [{}]: '{}'", psExecutable, command);

        List<String> commandList = new ArrayList<>();
        commandList.add(psExecutable);
        commandList.add("-NoProfile");
        commandList.add("-NonInteractive");
        commandList.add("-ExecutionPolicy");
        commandList.add("Bypass");
        commandList.add("-Command");
        commandList.add(command);

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
            return "ERROR: PowerShell command timed out after 60 seconds.\nPartial output:\n" + output;
        }

        int exitCode = process.exitValue();
        return "Exit code: " + exitCode + "\nOutput:\n" + (output.isEmpty() ? "(No output)" : output.toString());
    }

    public static String findPowerShellExecutable() {
        // Try pwsh (PowerShell Core 7+) first
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && pathEnv.contains("PowerShell\\7")) {
            return "pwsh.exe";
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows) {
            return "pwsh";
        }

        // Standard Windows PowerShell path
        File winPs = new File("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        if (winPs.exists() && winPs.canExecute()) {
            return winPs.getAbsolutePath();
        }

        return "powershell.exe";
    }
}

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
            Preferred command execution tool on Windows environments.
            Commands time out after 120 seconds unless "timeout_seconds" (max 900) is given; stdin is closed.
            Arguments JSON schema:
            {
              "command": "PowerShell command or script (e.g. Get-ChildItem, Select-String, Test-Path, mvn test)"
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
            return "ERROR: Empty powershell command provided.";
        }

        File workingDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        String blocked = ProcessRunner.guard("powershell", command, workingDir);
        if (blocked != null) return blocked;

        String psExecutable = findPowerShellExecutable();
        log.info("Executing PowerShell using [{}]: '{}'", psExecutable, command);
        try {
            ProcessRunner.Result result = ProcessRunner.run(
                    List.of(psExecutable, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command),
                    workingDir, ProcessRunner.timeoutFrom(json), nativeCharset());
            return result.describe("PowerShell command");
        } catch (java.io.IOException e) {
            // Missing executable: report it to the agent instead of failing the step
            return "ERROR: PowerShell is not available on this machine ('" + psExecutable + "' could not be started: "
                    + e.getMessage() + "). Use the 'bash' or 'terminal' tool instead.";
        }
    }

    /** Console output of Windows tools uses the system code page, not UTF-8. */
    static java.nio.charset.Charset nativeCharset() {
        try {
            String enc = System.getProperty("native.encoding");
            return enc != null ? java.nio.charset.Charset.forName(enc) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    public static String findPowerShellExecutable() {
        // Try pwsh (PowerShell Core 7+) first
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && pathEnv.contains("PowerShell\\7")) {
            return "pwsh.exe";
        }

        boolean isWindows = com.github.axiomate.agentic.ide.util.OSUtils.isWindows();
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


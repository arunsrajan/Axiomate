package com.github.axiomate.agentic.ide.features.security;

import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 31: Sandboxed execution by default.
 * Agent commands run in containers with network and filesystem allowlists.
 */
public class ExecutionSandbox {

    private static final Logger log = LoggerFactory.getLogger(ExecutionSandbox.class);
    private static ExecutionSandbox instance;

    public record SandboxValidationResult(
            boolean allowed,
            String commandOrPath,
            String violationReason
    ) {}

    private final Set<String> allowedCommands = new HashSet<>();
    private final List<String> networkAllowlist = new CopyOnWriteArrayList<>();
    private boolean sandboxEnforced = true;

    private ExecutionSandbox() {
        initDefaultAllowlists();
    }

    public static synchronized ExecutionSandbox getInstance() {
        if (instance == null) {
            instance = new ExecutionSandbox();
        }
        return instance;
    }

    private void initDefaultAllowlists() {
        // Allowed safe commands
        allowedCommands.add("mvn");
        allowedCommands.add("gradle");
        allowedCommands.add("javac");
        allowedCommands.add("java");
        allowedCommands.add("git");
        allowedCommands.add("npm");
        allowedCommands.add("node");
        allowedCommands.add("python");
        allowedCommands.add("pytest");
        allowedCommands.add("ls");
        allowedCommands.add("dir");
        allowedCommands.add("echo");

        // Allowed network endpoints
        networkAllowlist.add("repo.maven.apache.org");
        networkAllowlist.add("api.anthropic.com");
        networkAllowlist.add("api.openai.com");
        networkAllowlist.add("generativelanguage.googleapis.com");
        networkAllowlist.add("localhost");
        networkAllowlist.add("127.0.0.1");
    }

    public boolean isSandboxEnforced() { return sandboxEnforced; }
    public void setSandboxEnforced(boolean sandboxEnforced) { this.sandboxEnforced = sandboxEnforced; }

    public Set<String> getAllowedCommands() { return Collections.unmodifiableSet(allowedCommands); }
    public List<String> getNetworkAllowlist() { return Collections.unmodifiableList(networkAllowlist); }

    /**
     * Validates that an executed command is allowed by the container sandbox policy.
     */
    public SandboxValidationResult validateCommand(String rawCommandLine) {
        if (!sandboxEnforced) {
            return new SandboxValidationResult(true, rawCommandLine, null);
        }

        if (rawCommandLine == null || rawCommandLine.isBlank()) {
            return new SandboxValidationResult(false, "", "Empty command string");
        }

        String trimmed = rawCommandLine.trim();
        String baseCmd = trimmed.split("\\s+")[0].toLowerCase();
        if (baseCmd.endsWith(".exe") || baseCmd.endsWith(".cmd") || baseCmd.endsWith(".bat")) {
            baseCmd = baseCmd.substring(0, baseCmd.lastIndexOf('.'));
        }

        // Check for forbidden shell escapes or destructive root commands
        if (trimmed.contains("/etc/") || trimmed.contains("C:\\Windows\\System32") || trimmed.contains("format ") || trimmed.contains("rm -rf /")) {
            return new SandboxValidationResult(false, rawCommandLine, "Command violates filesystem container boundary");
        }

        if (!allowedCommands.contains(baseCmd)) {
            return new SandboxValidationResult(false, rawCommandLine,
                    String.format("Command '%s' is not in the sandbox container allowlist: %s", baseCmd, allowedCommands));
        }

        return new SandboxValidationResult(true, rawCommandLine, null);
    }

    /**
     * Validates that a file path is within the allowed workspace container root.
     */
    public SandboxValidationResult validatePathAccess(File targetFile) {
        if (!sandboxEnforced) {
            return new SandboxValidationResult(true, targetFile.getAbsolutePath(), null);
        }

        File workspaceRoot = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (workspaceRoot == null) {
            return new SandboxValidationResult(true, targetFile.getAbsolutePath(), null);
        }

        try {
            String rootCanon = workspaceRoot.getCanonicalPath().replace('\\', '/').toLowerCase();
            String targetCanon = targetFile.getCanonicalPath().replace('\\', '/').toLowerCase();

            if (targetCanon.startsWith(rootCanon)) {
                return new SandboxValidationResult(true, targetFile.getAbsolutePath(), null);
            }

            String tmpDir = System.getProperty("java.io.tmpdir");
            if (tmpDir != null) {
                String tmpCanon = new File(tmpDir).getCanonicalPath().replace('\\', '/').toLowerCase();
                if (targetCanon.startsWith(tmpCanon)) {
                    return new SandboxValidationResult(true, targetFile.getAbsolutePath(), null);
                }
            }

            return new SandboxValidationResult(false, targetFile.getAbsolutePath(),
                    "Path escapes workspace root sandbox: " + workspaceRoot.getAbsolutePath());
        } catch (Exception e) {
            return new SandboxValidationResult(false, targetFile.getAbsolutePath(), "Error resolving path in sandbox: " + e.getMessage());
        }
    }
}

package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.axiomate.agentic.ide.features.security.AuditTrailService;
import com.github.axiomate.agentic.ide.features.security.IrreversibleActionGate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the shell tools' processes: output is read on its own thread so the timeout always applies (also to
 * commands that never exit, like dev servers), output beyond the limit is drained and dropped so a chatty command
 * never blocks on a full pipe, stdin is closed so prompts fail fast instead of hanging, and a timeout kills the
 * whole process tree rather than just the shell.
 */
final class ProcessRunner {

    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    static final int MAX_TIMEOUT_SECONDS = 900;
    static final int MAX_OUTPUT_CHARS = 25_000;

    record Result(int exitCode, String output, boolean timedOut, boolean truncated, int timeoutSeconds) {

        /** The text the agent sees. */
        String describe(String what) {
            String out = output.isEmpty() ? "(No output)" : output;
            if (truncated) out += "\n[OUTPUT TRUNCATED after " + MAX_OUTPUT_CHARS + " characters]";
            if (timedOut) {
                return "ERROR: " + what + " timed out after " + timeoutSeconds + " seconds and was stopped. "
                        + "Pass \"timeout_seconds\" (up to " + MAX_TIMEOUT_SECONDS + ") for longer tasks; don't start "
                        + "servers or watchers that never exit.\nPartial output:\n" + out;
            }
            return "Exit code: " + exitCode + "\nOutput:\n" + out;
        }
    }

    private ProcessRunner() {
    }

    /** Optional "timeout_seconds" argument, clamped to 1..MAX. */
    static int timeoutFrom(JsonNode args) {
        if (args == null || !args.has("timeout_seconds")) return DEFAULT_TIMEOUT_SECONDS;
        int t = args.path("timeout_seconds").asInt(DEFAULT_TIMEOUT_SECONDS);
        return Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, t));
    }

    /**
     * Checks a command before it runs: destructive commands (force push, hard reset, recursive delete, dropping
     * tables) need the user's confirmation, and every command is written to the audit trail.
     *
     * @return null when the command may run, otherwise the message for the agent
     */
    static String guard(String toolName, String command, File workingDir) {
        String where = workingDir != null ? workingDir.getPath() : "workspace";
        // the command itself is what the user has to see when asked to confirm
        if (!IrreversibleActionGate.getInstance().checkAndConfirm(command, command)) {
            return "BLOCKED: This command can destroy data and was not confirmed by the user: " + command;
        }
        AuditTrailService.getInstance().recordEvent("AGENT", toolName.toUpperCase() + "_COMMAND", where, "Command: " + command);
        return null;
    }

    static Result run(List<String> command, File workingDir, int timeoutSeconds, Charset charset) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && workingDir.isDirectory()) pb.directory(workingDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getOutputStream().close(); // no interactive input: commands that prompt fail instead of hanging

        StringBuilder output = new StringBuilder();
        boolean[] truncated = {false};
        Thread reader = new Thread(() -> drain(process.getInputStream(), charset, output, truncated), "axiomate-tool-output");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            killTree(process); // the agent task was stopped
            throw e;
        }
        if (!finished) killTree(process);
        reader.join(2_000);
        String text;
        synchronized (output) {
            text = output.toString();
        }
        return new Result(finished ? process.exitValue() : -1, text, !finished, truncated[0], timeoutSeconds);
    }

    private static void drain(InputStream in, Charset charset, StringBuilder output, boolean[] truncated) {
        char[] buf = new char[8192];
        try (Reader r = new InputStreamReader(in, charset)) {
            int n;
            while ((n = r.read(buf)) >= 0) {
                synchronized (output) {
                    int room = MAX_OUTPUT_CHARS - output.length();
                    if (room > 0) output.append(buf, 0, Math.min(n, room));
                    if (n > room) truncated[0] = true; // keep reading so the process never blocks on a full pipe
                }
            }
        } catch (IOException ignored) {
            // process killed
        }
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}

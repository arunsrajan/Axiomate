package com.github.axiomate.agentic.ide.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes state files so that a crash or power loss mid-write never leaves a half-written file behind: the data goes
 * to a temporary file next to the target, which then replaces it in one rename.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * @param ownerOnly restrict the file to its owner (it holds API keys); ignored where POSIX permissions don't exist
     */
    public static void writeJson(ObjectMapper mapper, Path target, Object value, boolean ownerOnly) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + target.getFileName() + ".", ".tmp");
        try {
            if (ownerOnly) restrictToOwner(tmp);
            mapper.writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (ownerOnly) restrictToOwner(target);
    }

    /**
     * Keeps a copy of a file that could not be read, so replacing it with defaults never destroys the user's data.
     *
     * @return the backup's path, or null if it could not be made
     */
    public static Path backupUnreadable(Path file) {
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".unreadable-"
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            return null;
        }
    }

    /** One-line reason a state file could not be read, for a log message that does not need a stack trace. */
    public static String readFailureReason(Exception e) {
        String msg = e instanceof com.fasterxml.jackson.core.JsonProcessingException jpe
                ? jpe.getOriginalMessage() : e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }

    static void restrictToOwner(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the user profile folder is already private
        }
    }
}

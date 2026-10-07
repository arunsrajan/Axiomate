package com.github.axiomate.agentic.ide.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the checked-out git branch of a folder straight from {@code .git/HEAD}, without running git. Works from
 * subfolders, in linked worktrees and submodules ({@code .git} file with {@code gitdir:}), and for a detached HEAD.
 */
public final class GitBranch {

    private GitBranch() {
    }

    /** The branch name, a short commit id for a detached HEAD, or null when the folder is not in a git repository. */
    public static String of(File dir) {
        Path gitDir = findGitDir(dir);
        if (gitDir == null) return null;
        try {
            String head = Files.readString(gitDir.resolve("HEAD"), StandardCharsets.UTF_8).trim();
            if (head.startsWith("ref:")) {
                String ref = head.substring(4).trim();
                return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            }
            return head.length() >= 7 ? head.substring(0, 7) : (head.isEmpty() ? null : head);
        } catch (IOException e) {
            return null;
        }
    }

    /** True when {@link #of} returned a commit id rather than a branch. */
    public static boolean isDetached(String branch) {
        return branch != null && branch.matches("[0-9a-f]{7}");
    }

    static Path findGitDir(File start) {
        for (File d = start != null ? start.getAbsoluteFile() : null; d != null; d = d.getParentFile()) {
            Path dotGit = d.toPath().resolve(".git");
            if (Files.isDirectory(dotGit)) return dotGit;
            if (Files.isRegularFile(dotGit)) {
                try {
                    String content = Files.readString(dotGit, StandardCharsets.UTF_8).trim();
                    if (content.startsWith("gitdir:")) {
                        Path target = Path.of(content.substring(7).trim());
                        return target.isAbsolute() ? target : d.toPath().resolve(target).normalize();
                    }
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }
}

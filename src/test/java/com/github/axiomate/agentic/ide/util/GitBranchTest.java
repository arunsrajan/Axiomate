package com.github.axiomate.agentic.ide.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GitBranchTest {

    @Test
    void readsBranchFromRepoAndSubfolders(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/feature/refunds\n");
        Files.createDirectories(repo.resolve("src/main"));
        assertEquals("feature/refunds", GitBranch.of(repo.toFile()));
        assertEquals("feature/refunds", GitBranch.of(repo.resolve("src/main").toFile()), "found from a subfolder");
        assertFalse(GitBranch.isDetached("feature/refunds"));
    }

    @Test
    void detachedHeadShowsShortCommit(@TempDir Path repo) throws Exception {
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), "3f9a1c2b7e4d5f60718293a4b5c6d7e8f9012345\n");
        assertEquals("3f9a1c2", GitBranch.of(repo.toFile()));
        assertTrue(GitBranch.isDetached("3f9a1c2"));
    }

    @Test
    void worktreeGitFileIsFollowed(@TempDir Path root) throws Exception {
        Path gitDir = root.resolve("main/.git/worktrees/wt");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/hotfix\n");
        Path worktree = Files.createDirectories(root.resolve("wt"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../main/.git/worktrees/wt\n");
        assertEquals("hotfix", GitBranch.of(worktree.toFile()));
    }

    @Test
    void noRepositoryGivesNull(@TempDir Path dir) {
        assertNull(GitBranch.of(dir.toFile()));
        assertNull(GitBranch.of(null));
    }
}

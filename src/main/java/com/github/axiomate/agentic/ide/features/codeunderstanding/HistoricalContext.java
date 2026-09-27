package com.github.axiomate.agentic.ide.features.codeunderstanding;

import java.time.Instant;

/**
 * Historical provenance and context model for a code line or symbol.
 */
public record HistoricalContext(
        String filePath,
        int lineNumber,
        String lineContent,
        String commitHash,
        String author,
        Instant commitDate,
        String commitMessage,
        String prNumber,
        String prTitle,
        String ticketId,
        String designRationale
) {
    public String getShortHash() {
        return commitHash != null && commitHash.length() >= 7 ? commitHash.substring(0, 7) : commitHash;
    }
}

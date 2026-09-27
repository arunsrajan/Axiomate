package com.github.axiomate.agentic.ide.features.collaboration;

/**
 * Model representing a drafted reviewer response and code patch.
 */
public record ReviewResponseProposal(
        String reviewerCommentId,
        String reviewerName,
        String reviewerCommentText,
        String draftedAuthorReply,
        String proposedCodePatchDiff,
        boolean codeModificationRequired,
        String explanation
) {
}

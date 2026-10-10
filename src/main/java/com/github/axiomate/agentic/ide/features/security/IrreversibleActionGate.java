package com.github.axiomate.agentic.ide.features.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature 35: Irreversible-action gate.
 * Destructive operations such as dropping tables, force-pushing, or deleting files
 * always require explicit human confirmation.
 */
public class IrreversibleActionGate {

    private static final Logger log = LoggerFactory.getLogger(IrreversibleActionGate.class);
    private static IrreversibleActionGate instance;

    public interface ConfirmationHandler {
        boolean confirmAction(String operationType, String subject, String warning);
    }

    /** "git push" with --force, --force-with-lease or a short flag group containing f (-f, -uf); not "feature-fix". */
    private static final java.util.regex.Pattern FORCE_PUSH = java.util.regex.Pattern.compile(
            "\\bgit\\s+push\\b[^;&|]*(?<=\\s)(--force\\S*|-[a-z]*f[a-z]*)(?=\\s|$|[;&|])");
    private static final java.util.regex.Pattern HARD_RESET = java.util.regex.Pattern.compile(
            "\\breset\\s+--hard\\b|\\bgit\\s+clean\\b[^;&|]*(?<=\\s)-[a-z]*f");
    /** rm -r / -rf / -fr / --recursive, rmdir, del /f /s, Remove-Item -Recurse. */
    private static final java.util.regex.Pattern RECURSIVE_DELETE = java.util.regex.Pattern.compile(
            "\\brm\\s+(-[a-z]*r[a-z]*|--recursive)\\b|\\brmdir\\b|\\brd\\s+/s\\b|\\bdel\\s+/[fsq]\\b"
                    + "|\\bremove-item\\b[^;&|]*-recurse");

    private ConfirmationHandler confirmationHandler;

    private IrreversibleActionGate() {}

    public static synchronized IrreversibleActionGate getInstance() {
        if (instance == null) {
            instance = new IrreversibleActionGate();
        }
        return instance;
    }

    public void setConfirmationHandler(ConfirmationHandler handler) {
        this.confirmationHandler = handler;
    }

    /**
     * Evaluates a command or file operation to check if it represents an irreversible destructive action.
     */
    public GateDecision evaluate(String commandOrAction, String targetSubject) {
        String lower = (commandOrAction != null ? commandOrAction : "").toLowerCase();
        String subject = targetSubject != null ? targetSubject : "";

        if (lower.contains("drop table") || lower.contains("drop database") || lower.contains("truncate table")) {
            return GateDecision.destructive("DATABASE_DROP", subject,
                    "Database drop/truncate operation destroys relational table schema and all persisted records permanently.");
        }

        if (FORCE_PUSH.matcher(lower).find()) {
            return GateDecision.destructive("FORCE_PUSH", subject,
                    "Git force-push overwrites remote upstream git history and can erase team commits.");
        }

        if (HARD_RESET.matcher(lower).find()) {
            return GateDecision.destructive("HARD_RESET", subject,
                    "Hard git reset / clean discards all uncommitted modifications and untracked files irreversibly.");
        }

        if (lower.contains("delete_file") || RECURSIVE_DELETE.matcher(lower).find()) {
            return GateDecision.destructive("FILE_DELETION", subject,
                    "Permanently deletes files or directories from the workspace filesystem.");
        }

        return GateDecision.safe("STANDARD_OPERATION", subject);
    }

    /**
     * Checks if operation is allowed. If destructive, prompts the human confirmation handler.
     */
    public boolean checkAndConfirm(String commandOrAction, String targetSubject) {
        GateDecision decision = evaluate(commandOrAction, targetSubject);
        if (!decision.isDestructive()) {
            return true;
        }

        log.warn("Irreversible action detected: {} on {}", decision.operationType(), decision.targetSubject());
        if (confirmationHandler != null) {
            return confirmationHandler.confirmAction(decision.operationType(), decision.targetSubject(), decision.warningRationale());
        }

        // By default without handler, block destructive action for safety
        return false;
    }
}

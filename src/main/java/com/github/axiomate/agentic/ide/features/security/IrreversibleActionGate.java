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

        if (lower.contains("push") && (lower.contains("--force") || lower.contains("-f"))) {
            return GateDecision.destructive("FORCE_PUSH", subject,
                    "Git force-push overwrites remote upstream git history and can erase team commits.");
        }

        if (lower.contains("reset --hard") || lower.contains("clean -fdx")) {
            return GateDecision.destructive("HARD_RESET", subject,
                    "Hard git reset / clean discards all uncommitted modifications and untracked files irreversibly.");
        }

        if (lower.contains("delete_file") || lower.contains("rm -rf") || lower.contains("del /f") || lower.contains("rmdir")) {
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

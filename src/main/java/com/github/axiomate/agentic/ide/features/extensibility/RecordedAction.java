package com.github.axiomate.agentic.ide.features.extensibility;

import java.time.Instant;
import java.util.Map;

/**
 * An individual recorded user action during a workflow recording session.
 */
public record RecordedAction(
        int sequenceIndex,
        String actionType, // "OPEN_FILE", "EDIT_CODE", "RUN_COMMAND", "RUN_TEST"
        String targetSubject,
        Map<String, String> parameters,
        Instant timestamp
) {
}

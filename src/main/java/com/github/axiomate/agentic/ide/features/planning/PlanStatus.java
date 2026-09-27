package com.github.axiomate.agentic.ide.features.planning;

public enum PlanStatus {
    PENDING("⏳ Pending"),
    IN_PROGRESS("🔄 In Progress"),
    COMPLETED("✅ Completed"),
    BLOCKED("⛔ Blocked"),
    SKIPPED("⏭ Skipped");

    private final String displayLabel;

    PlanStatus(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}

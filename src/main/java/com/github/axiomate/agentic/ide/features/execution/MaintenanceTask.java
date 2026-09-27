package com.github.axiomate.agentic.ide.features.execution;

import java.time.Instant;

/**
 * Model representing a recurring scheduled maintenance agent task.
 */
public class MaintenanceTask {
    public enum TaskType { DEPENDENCY_BUMP, DEAD_CODE_SWEEP, FLAKY_TEST_TRIAGE, SECURITY_AUDIT, DOCS_REFRESH }

    private String id;
    private String name;
    private TaskType type;
    private String cronOrInterval; // e.g. "Every 24h", "0 0 * * *", "Weekly"
    private boolean enabled;
    private Instant lastRunAt;
    private String lastRunStatus;
    private String lastReportSummary;

    public MaintenanceTask(String id, String name, TaskType type, String cronOrInterval, boolean enabled) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.cronOrInterval = cronOrInterval;
        this.enabled = enabled;
        this.lastRunStatus = "Never Run";
        this.lastReportSummary = "Awaiting initial scheduled run";
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public TaskType getType() { return type; }
    public String getCronOrInterval() { return cronOrInterval; }
    public void setCronOrInterval(String cronOrInterval) { this.cronOrInterval = cronOrInterval; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }
    public String getLastReportSummary() { return lastReportSummary; }
    public void setLastReportSummary(String lastReportSummary) { this.lastReportSummary = lastReportSummary; }
}

package com.github.axiomate.agentic.ide.features.execution;

import java.time.Instant;

/**
 * Model representing a persistent, long-running agent background job.
 */
public class BackgroundJob {
    public enum JobStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    private String jobId;
    private String jobName;
    private String jobType; // e.g. "MIGRATION", "BATCH_REFACTOR", "SECURITY_SCAN"
    private JobStatus status;
    private int progressPercent;
    private String currentStage;
    private Instant startedAt;
    private Instant completedAt;
    private String completionReport;
    private String failureReason;

    public BackgroundJob(String jobId, String jobName, String jobType) {
        this.jobId = jobId;
        this.jobName = jobName;
        this.jobType = jobType;
        this.status = JobStatus.QUEUED;
        this.progressPercent = 0;
        this.currentStage = "Queued";
        this.startedAt = Instant.now();
    }

    public String getJobId() { return jobId; }
    public String getJobName() { return jobName; }
    public String getJobType() { return jobType; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = Math.max(0, Math.min(100, progressPercent)); }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getCompletionReport() { return completionReport; }
    public void setCompletionReport(String completionReport) { this.completionReport = completionReport; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}

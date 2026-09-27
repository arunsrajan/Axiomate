package com.github.axiomate.agentic.ide.features.planning;

import java.util.ArrayList;
import java.util.List;

/**
 * A discrete step in the Living Plan Canvas.
 */
public class PlanTask {
    private String id;
    private String title;
    private String description;
    private List<String> targetFiles;
    private List<String> identifiedRisks;
    private PlanStatus status;
    private int orderIndex;

    public PlanTask() {
        this.targetFiles = new ArrayList<>();
        this.identifiedRisks = new ArrayList<>();
        this.status = PlanStatus.PENDING;
    }

    public PlanTask(String id, String title, String description, List<String> targetFiles, List<String> identifiedRisks, int orderIndex) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.targetFiles = targetFiles != null ? new ArrayList<>(targetFiles) : new ArrayList<>();
        this.identifiedRisks = identifiedRisks != null ? new ArrayList<>(identifiedRisks) : new ArrayList<>();
        this.status = PlanStatus.PENDING;
        this.orderIndex = orderIndex;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTargetFiles() { return targetFiles; }
    public void setTargetFiles(List<String> targetFiles) { this.targetFiles = targetFiles != null ? targetFiles : new ArrayList<>(); }

    public List<String> getIdentifiedRisks() { return identifiedRisks; }
    public void setIdentifiedRisks(List<String> identifiedRisks) { this.identifiedRisks = identifiedRisks != null ? identifiedRisks : new ArrayList<>(); }

    public PlanStatus getStatus() { return status; }
    public void setStatus(PlanStatus status) { this.status = status; }

    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }

    public void addTargetFile(String file) {
        if (file != null && !targetFiles.contains(file)) {
            targetFiles.add(file);
        }
    }

    public void addRisk(String risk) {
        if (risk != null && !identifiedRisks.contains(risk)) {
            identifiedRisks.add(risk);
        }
    }
}

package com.github.axiomate.agentic.ide.features.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Feature 2: Living plan canvas.
 * An editable, visual plan (steps, files, risks) that updates as the agent works
 * and can be reordered mid-run.
 */
public class LivingPlanCanvas {

    private static final Logger log = LoggerFactory.getLogger(LivingPlanCanvas.class);
    private static LivingPlanCanvas instance;

    public interface PlanChangeListener {
        void onPlanChanged();
    }

    private final List<PlanTask> tasks = new CopyOnWriteArrayList<>();
    private final List<PlanChangeListener> listeners = new CopyOnWriteArrayList<>();
    private String planTitle = "Living Execution Plan";

    private LivingPlanCanvas() {}

    public static synchronized LivingPlanCanvas getInstance() {
        if (instance == null) {
            instance = new LivingPlanCanvas();
        }
        return instance;
    }

    public void addListener(PlanChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PlanChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (PlanChangeListener l : listeners) {
            try {
                l.onPlanChanged();
            } catch (Exception e) {
                log.warn("Error notifying plan change listener", e);
            }
        }
    }

    public String getPlanTitle() { return planTitle; }
    public void setPlanTitle(String planTitle) {
        this.planTitle = planTitle;
        notifyListeners();
    }

    public List<PlanTask> getTasks() {
        List<PlanTask> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingInt(PlanTask::getOrderIndex));
        return sorted;
    }

    public void setTasks(List<PlanTask> newTasks) {
        tasks.clear();
        if (newTasks != null) {
            for (int i = 0; i < newTasks.size(); i++) {
                PlanTask t = newTasks.get(i);
                t.setOrderIndex(i);
                tasks.add(t);
            }
        }
        notifyListeners();
    }

    public void addTask(PlanTask task) {
        if (task != null) {
            task.setOrderIndex(tasks.size());
            tasks.add(task);
            notifyListeners();
        }
    }

    public void removeTask(String taskId) {
        tasks.removeIf(t -> t.getId().equalsIgnoreCase(taskId));
        reindexTasks();
        notifyListeners();
    }

    public void moveTaskUp(int index) {
        List<PlanTask> current = getTasks();
        if (index > 0 && index < current.size()) {
            PlanTask t1 = current.get(index - 1);
            PlanTask t2 = current.get(index);
            t1.setOrderIndex(index);
            t2.setOrderIndex(index - 1);
            notifyListeners();
        }
    }

    public void moveTaskDown(int index) {
        List<PlanTask> current = getTasks();
        if (index >= 0 && index < current.size() - 1) {
            PlanTask t1 = current.get(index);
            PlanTask t2 = current.get(index + 1);
            t1.setOrderIndex(index + 1);
            t2.setOrderIndex(index);
            notifyListeners();
        }
    }

    public void updateTaskStatus(String taskId, PlanStatus status) {
        for (PlanTask t : tasks) {
            if (t.getId().equalsIgnoreCase(taskId)) {
                t.setStatus(status);
                notifyListeners();
                return;
            }
        }
    }

    public PlanTask findTask(String taskId) {
        for (PlanTask t : tasks) {
            if (t.getId().equalsIgnoreCase(taskId)) return t;
        }
        return null;
    }

    public void clear() {
        tasks.clear();
        notifyListeners();
    }

    private void reindexTasks() {
        List<PlanTask> sorted = getTasks();
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setOrderIndex(i);
        }
    }

    /**
     * Initializes a sample or generated plan from a prompt.
     */
    public void generatePlanFromPrompt(String prompt, String activeFile) {
        tasks.clear();
        String safeFile = (activeFile != null && !activeFile.isBlank()) ? activeFile : "src/App.java";
        this.planTitle = "Plan: " + (prompt.length() > 50 ? prompt.substring(0, 47) + "..." : prompt);

        tasks.add(new PlanTask(
                "task-1",
                "1. Structural Analysis & Pre-flight Inspection",
                "Inspect dependencies, signatures, and imports across affected components",
                List.of(safeFile),
                List.of("Unintended side effects on dependent modules"),
                0
        ));

        tasks.add(new PlanTask(
                "task-2",
                "2. Implementation & Precise Code Editing",
                "Apply core architectural enhancements and modern pattern adaptations",
                List.of(safeFile),
                List.of("Compile error risk", "Null pointer hazards"),
                1
        ));

        tasks.add(new PlanTask(
                "task-3",
                "3. Unit Verification & Regression Testing",
                "Synthesize and run automated JUnit test cases to ensure green state",
                List.of(safeFile.replace(".java", "Test.java")),
                List.of("Flaky test execution", "Mock mismatch"),
                2
        ));

        tasks.add(new PlanTask(
                "task-4",
                "4. Post-execution Cleanup & Documentation",
                "Validate coding conventions and update documentation",
                List.of(safeFile),
                List.of("Style drift"),
                3
        ));

        notifyListeners();
    }
}

package com.github.axiomate.agentic.ide.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent runs (one per prompt sent) for the Tasks panel: what is running now and how recent runs ended.
 * Listeners may be called from any thread.
 */
public final class AgentRunTracker {

    public enum Status {RUNNING, COMPLETED, FAILED, STOPPED}

    public static final class Run {
        private final long id;
        private final String sessionId;
        private final String sessionName;
        private final String prompt;
        private final ExecutionMode mode;
        private final Instant startedAt = Instant.now();
        private volatile Instant endedAt;
        private volatile Status status = Status.RUNNING;
        private volatile int toolCalls;
        private volatile String lastTool;
        private volatile String detail;

        Run(long id, String sessionId, String sessionName, String prompt, ExecutionMode mode) {
            this.id = id;
            this.sessionId = sessionId;
            this.sessionName = sessionName;
            this.prompt = prompt;
            this.mode = mode;
        }

        public long id() { return id; }
        public String sessionId() { return sessionId; }
        public String sessionName() { return sessionName; }
        public String prompt() { return prompt; }
        public ExecutionMode mode() { return mode; }
        public Instant startedAt() { return startedAt; }
        public Instant endedAt() { return endedAt; }
        public Status status() { return status; }
        public int toolCalls() { return toolCalls; }
        public String lastTool() { return lastTool; }
        public String detail() { return detail; }

        public Duration elapsed() {
            return Duration.between(startedAt, endedAt != null ? endedAt : Instant.now());
        }
    }

    static final int MAX_RUNS = 30;
    private static final int MAX_PROMPT = 160;
    private static AgentRunTracker instance;

    private final List<Run> runs = new ArrayList<>(); // newest first
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long nextId = 1;

    private AgentRunTracker() {
    }

    public static synchronized AgentRunTracker getInstance() {
        if (instance == null) instance = new AgentRunTracker();
        return instance;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public Run start(String sessionId, String sessionName, String prompt, ExecutionMode mode) {
        String p = prompt == null ? "" : prompt.strip().replaceAll("\\s+", " ");
        if (p.length() > MAX_PROMPT) p = p.substring(0, MAX_PROMPT - 1) + "…";
        Run run;
        synchronized (this) {
            // Only one task runs at a time: one still marked running was superseded
            for (Run r : runs) {
                if (r.status == Status.RUNNING) end(r, Status.STOPPED, "Superseded");
            }
            run = new Run(nextId++, sessionId, sessionName, p, mode != null ? mode : ExecutionMode.AUTO);
            runs.add(0, run);
            while (runs.size() > MAX_RUNS) runs.remove(runs.size() - 1);
        }
        notifyListeners();
        return run;
    }

    public void toolCall(Run run, String toolName) {
        if (run == null || run.status != Status.RUNNING) return;
        run.toolCalls++;
        run.lastTool = toolName;
        notifyListeners();
    }

    /** Ends a run; a run that already ended keeps its first outcome. */
    public void finish(Run run, Status status, String detail) {
        if (run == null) return;
        synchronized (this) {
            if (run.status != Status.RUNNING) return;
            end(run, status, detail);
        }
        notifyListeners();
    }

    public synchronized Run running() {
        for (Run r : runs) {
            if (r.status == Status.RUNNING) return r;
        }
        return null;
    }

    /** Newest first. */
    public synchronized List<Run> runs() {
        return List.copyOf(runs);
    }

    public void clearFinished() {
        synchronized (this) {
            runs.removeIf(r -> r.status != Status.RUNNING);
        }
        notifyListeners();
    }

    private static void end(Run run, Status status, String detail) {
        run.status = status;
        run.detail = detail;
        run.endedAt = Instant.now();
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a broken listener must not stop the others
            }
        }
    }
}

package com.github.axiomate.agentic.ide.agent.session;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Changes to a session's task list, from the agent's task_list tool or the Tasks panel. Every change is saved with
 * the session and announced to listeners (which may run on any thread).
 */
public final class TaskBoard {

    private static TaskBoard instance;

    static final int MAX_TASKS = 50;
    static final int MAX_TITLE = 200;
    private static final Pattern NUMBERED = Pattern.compile("^ {0,3}(\\d{1,2})[.)]\\s+(.+)$");

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private TaskBoard() {
    }

    public static synchronized TaskBoard getInstance() {
        if (instance == null) instance = new TaskBoard();
        return instance;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Replaces the whole list (the agent's "set" action). */
    public void replace(AgentSession session, List<SessionTask> tasks) {
        if (session == null) return;
        List<SessionTask> kept = new ArrayList<>();
        for (SessionTask t : tasks) {
            if (t != null && !t.getTitle().isBlank() && kept.size() < MAX_TASKS) {
                t.setTitle(clip(t.getTitle()));
                kept.add(t);
            }
        }
        session.setTasks(kept);
        changed();
    }

    public SessionTask add(AgentSession session, String title, String source) {
        if (session == null || title == null || title.isBlank() || session.getTasks().size() >= MAX_TASKS) return null;
        SessionTask t = new SessionTask(clip(title), SessionTask.Status.PENDING, source);
        session.getTasks().add(t);
        changed();
        return t;
    }

    public boolean setStatus(AgentSession session, String taskId, SessionTask.Status status) {
        SessionTask t = find(session, taskId);
        if (t == null || status == null) return false;
        t.setStatus(status);
        changed();
        return true;
    }

    public boolean rename(AgentSession session, String taskId, String title) {
        SessionTask t = find(session, taskId);
        if (t == null || title == null || title.isBlank()) return false;
        t.setTitle(clip(title));
        changed();
        return true;
    }

    public boolean remove(AgentSession session, String taskId) {
        if (session == null) return false;
        boolean removed = session.getTasks().removeIf(t -> t.getId().equals(taskId));
        if (removed) changed();
        return removed;
    }

    public int clearCompleted(AgentSession session) {
        if (session == null) return 0;
        int before = session.getTasks().size();
        session.getTasks().removeIf(SessionTask::isDone);
        int removed = before - session.getTasks().size();
        if (removed > 0) changed();
        return removed;
    }

    /** True while the session has a step that is not done yet. */
    public static boolean hasOpenTasks(AgentSession session) {
        return session != null && session.getTasks().stream().anyMatch(t -> !t.isDone());
    }

    /** "3/7 done" style counts: {done, total}. */
    public static int[] progress(AgentSession session) {
        if (session == null) return new int[]{0, 0};
        int done = (int) session.getTasks().stream().filter(SessionTask::isDone).count();
        return new int[]{done, session.getTasks().size()};
    }

    /**
     * The top-level numbered steps of a plan ("1. Add the field", "2) Write the test"), outside code blocks, with
     * Markdown emphasis removed. Nested and indented lists are details of a step, not steps.
     */
    public static List<String> stepsFromPlan(String text) {
        List<String> steps = new ArrayList<>();
        if (text == null) return steps;
        boolean inCode = false;
        int stepIndent = -1; // set by the first numbered item: deeper items are details of a step
        for (String line : text.split("\\R")) {
            if (line.strip().startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) continue;
            Matcher m = NUMBERED.matcher(line);
            int indent = line.length() - line.stripLeading().length();
            if (m.matches() && (stepIndent < 0 || indent == stepIndent)) {
                stepIndent = indent;
                String step = m.group(2).replace("**", "").replace("__", "").strip();
                if (!step.isEmpty() && steps.size() < MAX_TASKS) steps.add(clip(step));
            }
        }
        return steps;
    }

    /** The list as the agent sees it: "1. [x] Title". */
    public static String describe(List<SessionTask> tasks) {
        if (tasks.isEmpty()) return "(no tasks)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            SessionTask t = tasks.get(i);
            String box = switch (t.getStatus()) {
                case COMPLETED -> "[x]";
                case IN_PROGRESS -> "[~]";
                default -> "[ ]";
            };
            sb.append(i + 1).append(". ").append(box).append(' ').append(t.getTitle()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static SessionTask find(AgentSession session, String taskId) {
        if (session == null || taskId == null) return null;
        for (SessionTask t : session.getTasks()) {
            if (t.getId().equals(taskId)) return t;
        }
        return null;
    }

    private static String clip(String title) {
        String t = title.strip().replaceAll("\\s+", " ");
        return t.length() > MAX_TITLE ? t.substring(0, MAX_TITLE - 1) + "…" : t;
    }

    private void changed() {
        SessionManager.getInstance().autoSaveCurrentProjectSessions();
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a broken listener must not stop the others
            }
        }
    }
}

package com.github.axiomate.agentic.ide.agent;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;

/**
 * The session a running agent task belongs to, for tools that work on "the current session" (task_list): switching
 * sessions in the UI while a task runs must not send its changes to the newly opened session.
 */
public final class AgentTaskContext {

    private static final ThreadLocal<AgentSession> SESSION = new ThreadLocal<>();

    private AgentTaskContext() {
    }

    public static void set(AgentSession session) {
        SESSION.set(session);
    }

    public static void clear() {
        SESSION.remove();
    }

    /** The running task's session, or the active session outside a task. */
    public static AgentSession session() {
        AgentSession s = SESSION.get();
        return s != null ? s : SessionManager.getInstance().getActiveSession();
    }
}

package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.ui.components.ImplementationPlanPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * The implementation plan of a session in its own window, beside the IDE (not modal): one window per session,
 * brought to the front and updated when a revised plan arrives.
 */
public class ImplementationPlanDialog extends JDialog {

    private static final Map<String, ImplementationPlanDialog> OPEN = new HashMap<>();

    private final ImplementationPlanPanel panel;

    private ImplementationPlanDialog(Window owner, AgentSession session, ImplementationPlanPanel.Actions actions) {
        super(owner, title(session), ModalityType.MODELESS);
        panel = new ImplementationPlanPanel(session.getId(), session.getName(), session.getImplementationPlan());
        panel.setActions(actions);
        panel.setCloseHandler(this::dispose);
        setContentPane(panel);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(980, 680);
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the session's plan: opens its window, or updates the open one with the latest plan and brings it to the
     * front. Does nothing without a screen. Call on the event thread.
     */
    public static ImplementationPlanPanel open(Window owner, AgentSession session, ImplementationPlanPanel.Actions actions) {
        if (session == null || GraphicsEnvironment.isHeadless()) return null;
        ImplementationPlanDialog dialog = OPEN.get(session.getId());
        if (dialog == null || !dialog.isDisplayable()) {
            dialog = new ImplementationPlanDialog(owner, session, actions);
            OPEN.put(session.getId(), dialog);
        } else {
            dialog.setTitle(title(session));
            dialog.panel.setActions(actions);
            dialog.panel.setPlan(session.getName(), session.getImplementationPlan());
        }
        dialog.setVisible(true);
        dialog.toFront();
        return dialog.panel;
    }

    /** Closing forgets the window at once (a window event would only arrive later), so reopening builds a new one. */
    @Override
    public void dispose() {
        OPEN.remove(panel.getSessionId(), this);
        super.dispose();
    }

    /** The open plan window's panel of a session, or null. */
    public static ImplementationPlanPanel find(String sessionId) {
        ImplementationPlanDialog dialog = sessionId != null ? OPEN.get(sessionId) : null;
        return dialog != null ? dialog.panel : null;
    }

    private static String title(AgentSession session) {
        return "Implementation plan — " + session.getName();
    }
}

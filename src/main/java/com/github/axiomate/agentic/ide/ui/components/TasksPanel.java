package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentRunTracker;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.agent.session.SessionTask;
import com.github.axiomate.agentic.ide.agent.session.TaskBoard;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.ui.util.WrapLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tasks sidebar (Alt+8): the active session's task list, kept by the agent's task_list tool and by the user, and
 * the agent's activity: the task running now (with Stop) and how recent runs ended.
 */
public class TasksPanel extends JPanel {

    static final String CONTINUE_PROMPT = "Continue with the next pending task in the task list.";

    private final IdeActions ideActions;
    private final JLabel sessionLabel = new JLabel(" ");
    private final JLabel progressLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextField addField = new JTextField();
    private final JPanel taskList = new ScrollablePanel(null);
    private final JPanel runList = new ScrollablePanel(null);
    private final JButton continueBtn = new JButton("Continue with next task");
    private final JButton stopBtn = new JButton("Stop");
    private final JLabel runningLabel = new JLabel(" ");
    private Consumer<String> promptSender = p -> { };

    public TasksPanel(IdeActions ideActions) {
        super(new BorderLayout());
        this.ideActions = ideActions != null ? ideActions : IdeActions.NONE;
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        JLabel title = new JLabel("Tasks");
        title.setFont(UIUtils.uiFont(Font.BOLD, 13f));
        sessionLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        progressLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        progressBar.setPreferredSize(new Dimension(10, 6));
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        for (JComponent c : List.of(title, sessionLabel, progressLabel, progressBar)) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            north.add(c);
            north.add(Box.createVerticalStrut(3));
        }
        addField.putClientProperty("JTextField.placeholderText", "Add a task… (Enter)");
        addField.addActionListener(e -> addTask(addField.getText()));
        addField.setAlignmentX(LEFT_ALIGNMENT);
        addField.setMaximumSize(new Dimension(Integer.MAX_VALUE, addField.getPreferredSize().height));
        north.add(addField);
        add(north, BorderLayout.NORTH);

        taskList.setLayout(new BoxLayout(taskList, BoxLayout.Y_AXIS));
        runList.setLayout(new BoxLayout(runList, BoxLayout.Y_AXIS));
        JPanel taskButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        taskButtons.setOpaque(false);
        continueBtn.setToolTipText("Ask the agent to work on the next pending task");
        continueBtn.addActionListener(e -> promptSender.accept(CONTINUE_PROMPT));
        JButton clearDone = new JButton("Clear completed");
        clearDone.addActionListener(e -> TaskBoard.getInstance().clearCompleted(session()));
        taskButtons.add(continueBtn);
        taskButtons.add(clearDone);

        JPanel tasksSection = new JPanel(new BorderLayout());
        tasksSection.setOpaque(false);
        tasksSection.add(ScrollablePanel.verticalScroll(taskList), BorderLayout.CENTER);
        tasksSection.add(taskButtons, BorderLayout.SOUTH);

        JPanel activity = new JPanel(new BorderLayout(0, 4));
        activity.setOpaque(false);
        JPanel activityHeader = new JPanel(new BorderLayout(6, 0));
        activityHeader.setOpaque(false);
        JLabel activityTitle = new JLabel("Agent activity");
        activityTitle.setFont(UIUtils.uiFont(Font.BOLD, 12f));
        JButton clearRuns = new JButton("Clear");
        clearRuns.setToolTipText("Forget finished runs");
        clearRuns.addActionListener(e -> AgentRunTracker.getInstance().clearFinished());
        activityHeader.add(activityTitle, BorderLayout.WEST);
        activityHeader.add(clearRuns, BorderLayout.EAST);
        JPanel running = new JPanel(new BorderLayout(6, 0));
        running.setOpaque(false);
        runningLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        stopBtn.setToolTipText("Stop the running agent task");
        stopBtn.addActionListener(e -> this.ideActions.stopAgent());
        running.add(runningLabel, BorderLayout.CENTER);
        running.add(stopBtn, BorderLayout.EAST);
        JPanel activityTop = new JPanel(new BorderLayout(0, 4));
        activityTop.setOpaque(false);
        activityTop.add(activityHeader, BorderLayout.NORTH);
        activityTop.add(running, BorderLayout.SOUTH);
        activity.add(activityTop, BorderLayout.NORTH);
        activity.add(ScrollablePanel.verticalScroll(runList), BorderLayout.CENTER);
        activity.setBorder(new EmptyBorder(8, 0, 0, 0));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tasksSection, activity);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);

        // Changes arrive from the agent's thread; Swing is only touched on the event thread
        TaskBoard.getInstance().addListener(() -> UIUtils.onEdt(this::refresh));
        AgentRunTracker.getInstance().addListener(() -> UIUtils.onEdt(this::refreshRuns));
        SessionManager.getInstance().addSessionChangeListener(() -> UIUtils.onEdt(this::refresh));
        new Timer(1000, e -> {
            if (isShowing() && AgentRunTracker.getInstance().running() != null) refreshRunning();
        }).start();
        refresh();
    }

    /** Where "Continue with next task" sends its prompt (the agent panel). */
    public void setPromptSender(Consumer<String> sender) {
        this.promptSender = sender != null ? sender : p -> { };
    }

    public void refresh() {
        AgentSession s = session();
        sessionLabel.setText(s != null ? "Session: " + s.getName() + " · " + s.getExecutionMode().label() + " mode" : "No session");
        sessionLabel.setForeground(UIUtils.mutedForeground());
        int[] p = TaskBoard.progress(s);
        progressLabel.setText(p[1] == 0 ? "No tasks yet — the agent adds its plan here, or add your own."
                : p[0] + " of " + p[1] + " done");
        progressLabel.setForeground(UIUtils.mutedForeground());
        progressBar.setValue(p[1] == 0 ? 0 : p[0] * 100 / p[1]);
        progressBar.setVisible(p[1] > 0);
        continueBtn.setEnabled(TaskBoard.hasOpenTasks(s) && AgentRunTracker.getInstance().running() == null);

        taskList.removeAll();
        if (s != null) {
            List<SessionTask> tasks = s.getTasks();
            for (int i = 0; i < tasks.size(); i++) taskList.add(taskRow(s, tasks.get(i), i + 1));
        }
        taskList.revalidate();
        taskList.repaint();
        refreshRuns();
    }

    /** Titles with a status mark, as listed: "[x] Title", "[~] Title", "[ ] Title". */
    List<String> listedTasks() {
        List<String> out = new ArrayList<>();
        AgentSession s = session();
        if (s == null) return out;
        for (SessionTask t : s.getTasks()) {
            out.add((t.isDone() ? "[x] " : t.getStatus() == SessionTask.Status.IN_PROGRESS ? "[~] " : "[ ] ") + t.getTitle());
        }
        return out;
    }

    void addTask(String title) {
        if (title == null || title.isBlank()) return;
        TaskBoard.getInstance().add(session(), title, "user");
        addField.setText("");
    }

    private AgentSession session() {
        return SessionManager.getInstance().getActiveSession();
    }

    private JComponent taskRow(AgentSession s, SessionTask t, int number) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(2, 0, 2, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        JCheckBox box = new JCheckBox();
        box.setOpaque(false);
        box.setSelected(t.isDone());
        box.setToolTipText(t.isDone() ? "Mark as not done" : "Mark as done");
        box.addActionListener(e -> TaskBoard.getInstance().setStatus(s, t.getId(),
                box.isSelected() ? SessionTask.Status.COMPLETED : SessionTask.Status.PENDING));
        String text = escape(t.getTitle());
        String status = switch (t.getStatus()) {
            case IN_PROGRESS -> " <span style='color:#d97706'>● in progress</span>";
            case COMPLETED -> "";
            default -> "";
        };
        JLabel label = new JLabel("<html>" + number + ". " + (t.isDone() ? "<s>" + text + "</s>" : text) + status
                + ("agent".equals(t.getSource()) ? "" : " <span style='color:gray'>(you)</span>") + "</html>");
        label.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        if (t.isDone()) label.setForeground(UIUtils.mutedForeground());
        JPopupMenu menu = new JPopupMenu();
        for (SessionTask.Status st : SessionTask.Status.values()) {
            JMenuItem item = new JMenuItem("Mark " + st.name().toLowerCase().replace('_', ' '));
            item.setEnabled(st != t.getStatus());
            item.addActionListener(e -> TaskBoard.getInstance().setStatus(s, t.getId(), st));
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem rename = new JMenuItem("Rename…");
        rename.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Task", t.getTitle());
            if (name != null) TaskBoard.getInstance().rename(s, t.getId(), name);
        });
        JMenuItem ask = new JMenuItem("Ask the agent to do this task");
        ask.addActionListener(e -> promptSender.accept("Work on task " + number + " of the task list: " + t.getTitle()));
        ask.setEnabled(!t.isDone() && AgentRunTracker.getInstance().running() == null);
        JMenuItem delete = new JMenuItem("Delete");
        delete.addActionListener(e -> TaskBoard.getInstance().remove(s, t.getId()));
        menu.add(rename);
        menu.add(ask);
        menu.add(delete);
        row.setComponentPopupMenu(menu);
        label.setInheritsPopupMenu(true);
        box.setInheritsPopupMenu(true);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) rename.doClick();
            }
        });
        row.add(box, BorderLayout.WEST);
        row.add(label, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 4));
        return row;
    }

    private void refreshRunning() {
        AgentRunTracker.Run r = AgentRunTracker.getInstance().running();
        stopBtn.setVisible(r != null);
        if (r == null) {
            runningLabel.setText("Idle");
            runningLabel.setForeground(UIUtils.mutedForeground());
            return;
        }
        runningLabel.setText("<html><b>● Running</b> · " + escape(r.sessionName()) + " · " + r.mode().label()
                + " · " + format(r.elapsed()) + " · " + r.toolCalls() + " tool call" + (r.toolCalls() == 1 ? "" : "s")
                + (r.lastTool() != null ? " · last: " + escape(r.lastTool()) : "") + "</html>");
        runningLabel.setForeground(UIUtils.ACCENT_COLOR);
    }

    private void refreshRuns() {
        refreshRunning();
        continueBtn.setEnabled(TaskBoard.hasOpenTasks(session()) && AgentRunTracker.getInstance().running() == null);
        runList.removeAll();
        for (AgentRunTracker.Run r : AgentRunTracker.getInstance().runs()) {
            if (r.status() == AgentRunTracker.Status.RUNNING) continue;
            String mark = switch (r.status()) {
                case COMPLETED -> "<span style='color:#16a34a'>✔ Completed</span>";
                case FAILED -> "<span style='color:#dc2626'>✖ Failed</span>";
                case STOPPED -> "<span style='color:gray'>■ Stopped</span>";
                default -> "";
            };
            JLabel l = new JLabel("<html>" + mark + " · " + escape(r.prompt()) + "<br><span style='color:gray'>"
                    + escape(r.sessionName()) + " · " + r.mode().label() + " · " + format(r.elapsed()) + " · "
                    + r.toolCalls() + " tool call" + (r.toolCalls() == 1 ? "" : "s")
                    + (r.detail() != null && r.status() == AgentRunTracker.Status.FAILED ? " · " + escape(clip(r.detail())) : "")
                    + "</span></html>");
            l.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
            l.setBorder(new EmptyBorder(3, 0, 3, 0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            l.setToolTipText("Click to open the session");
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            l.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SessionManager.getInstance().findSession(r.sessionId()) != null) {
                        SessionManager.getInstance().switchSession(r.sessionId());
                    }
                }
            });
            runList.add(l);
        }
        runList.revalidate();
        runList.repaint();
    }

    static String format(Duration d) {
        long s = Math.max(0, d.getSeconds());
        return s < 60 ? s + "s" : s < 3600 ? (s / 60) + "m " + (s % 60) + "s" : (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    private static String clip(String s) {
        return s.length() > 120 ? s.substring(0, 119) + "…" : s;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.ExecutionMode;
import com.github.axiomate.agentic.ide.agent.session.TaskBoard;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contents of the implementation plan window: the plan (rendered, or as Markdown to edit), its steps with
 * checkboxes, feedback for a revision, and the buttons that carry the plan out.
 */
public class ImplementationPlanPanel extends JPanel {

    /** What the plan window asks the IDE to do. */
    public interface Actions {
        /**
         * @param steps the steps chosen to implement; they become the session's task list
         */
        void implement(ExecutionMode mode, String prompt, List<String> steps);

        /** Sends a revision request; the session stays in Plan mode and the window shows the revised plan. */
        void revise(String prompt);

        void showTasks();
    }

    static final String IMPLEMENT_PROMPT = "Implement the plan above, step by step. Mark each task in_progress "
            + "when you start it and completed when it is done.";
    private static final String VIEW = "view";
    private static final String EDIT = "edit";

    private final String sessionId;
    private String originalPlan = "";
    private Actions actions = new Actions() {
        public void implement(ExecutionMode mode, String prompt, List<String> steps) { }
        public void revise(String prompt) { }
        public void showTasks() { }
    };
    private Runnable closeHandler = () -> { };

    private final JLabel titleLabel = new JLabel();
    private final JLabel metaLabel = new JLabel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JPanel rendered = new ScrollablePanel(null);
    final JTextArea editor = new JTextArea();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    final JToggleButton editToggle = new JToggleButton("Edit");
    private final JPanel stepsBox = new ScrollablePanel(null);
    private final List<JCheckBox> stepChecks = new ArrayList<>();
    /** Steps the user unticked, by text: kept across edits of the plan, cleared by a new plan. */
    private final Set<String> unticked = new HashSet<>();
    final JTextField feedbackField = new JTextField();
    final JButton implementAuto = new JButton("Implement (Auto)");
    final JButton implementManual = new JButton("Implement (Manual)");
    final JButton reviseBtn = new JButton("Revise plan");
    final JButton copyBtn = new JButton("Copy");
    final JButton saveBtn = new JButton("Save as Markdown…");
    final JButton tasksBtn = new JButton("Show tasks");
    final JButton closeBtn = new JButton("Close");
    private boolean busy;

    public ImplementationPlanPanel(String sessionId, String sessionName, String plan) {
        super(new BorderLayout(0, 8));
        this.sessionId = sessionId;
        setBorder(new EmptyBorder(12, 14, 10, 14));

        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.setOpaque(false);
        titleLabel.setFont(UIUtils.uiFont(Font.BOLD, 15f));
        metaLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(metaLabel, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        rendered.setLayout(new BoxLayout(rendered, BoxLayout.Y_AXIS));
        rendered.setBorder(new EmptyBorder(6, 6, 6, 6));
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setBorder(new EmptyBorder(6, 6, 6, 6));
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { planEdited(); }
            public void removeUpdate(DocumentEvent e) { planEdited(); }
            public void changedUpdate(DocumentEvent e) { }
        });
        center.add(ScrollablePanel.verticalScroll(rendered), VIEW);
        center.add(new JScrollPane(editor), EDIT);
        editToggle.setToolTipText("Change the plan's Markdown before implementing it");
        editToggle.addActionListener(e -> {
            if (!editToggle.isSelected()) render(editor.getText());
            cards.show(center, editToggle.isSelected() ? EDIT : VIEW);
            editToggle.setText(editToggle.isSelected() ? "Preview" : "Edit");
        });

        JPanel stepsPanel = new JPanel(new BorderLayout(0, 4));
        stepsPanel.setOpaque(false);
        stepsPanel.setBorder(new EmptyBorder(0, 8, 0, 0));
        JLabel stepsTitle = new JLabel("Steps to implement");
        stepsTitle.setFont(UIUtils.uiFont(Font.BOLD, 12f));
        JPanel stepsHeader = new JPanel(new BorderLayout());
        stepsHeader.setOpaque(false);
        JPanel allNone = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        allNone.setOpaque(false);
        JButton all = new JButton("All");
        JButton none = new JButton("None");
        all.addActionListener(e -> selectAll(true));
        none.addActionListener(e -> selectAll(false));
        allNone.add(all);
        allNone.add(none);
        stepsHeader.add(stepsTitle, BorderLayout.WEST);
        stepsHeader.add(allNone, BorderLayout.EAST);
        stepsBox.setLayout(new BoxLayout(stepsBox, BoxLayout.Y_AXIS));
        stepsPanel.add(stepsHeader, BorderLayout.NORTH);
        stepsPanel.add(ScrollablePanel.verticalScroll(stepsBox), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, stepsPanel);
        split.setResizeWeight(0.68);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JPanel feedbackRow = new JPanel(new BorderLayout(6, 0));
        feedbackRow.setOpaque(false);
        feedbackField.putClientProperty("JTextField.placeholderText",
                "What should change? Revise sends this to the agent, which stays in Plan mode");
        feedbackField.addActionListener(e -> revise());
        feedbackField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateButtons(); }
            public void removeUpdate(DocumentEvent e) { updateButtons(); }
            public void changedUpdate(DocumentEvent e) { }
        });
        reviseBtn.addActionListener(e -> revise());
        JLabel feedbackLabel = new JLabel("What should change?");
        feedbackLabel.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        feedbackLabel.setLabelFor(feedbackField);
        feedbackLabel.setBorder(new EmptyBorder(0, 0, 0, 4));
        feedbackRow.add(feedbackLabel, BorderLayout.WEST);
        feedbackRow.add(feedbackField, BorderLayout.CENTER);
        feedbackRow.add(reviseBtn, BorderLayout.EAST);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        copyBtn.setToolTipText("Copy the plan as Markdown");
        copyBtn.addActionListener(e -> {
            if (AIAgentPanel.copyToClipboard(editor.getText())) Toast.success(this, "Plan copied");
        });
        saveBtn.addActionListener(e -> saveAsMarkdown());
        tasksBtn.addActionListener(e -> actions.showTasks());
        left.add(editToggle);
        left.add(copyBtn);
        left.add(saveBtn);
        left.add(tasksBtn);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        implementAuto.setToolTipText("Switch to Auto mode and carry out the plan");
        implementManual.setToolTipText("Switch to Manual mode: you approve each change while the plan is carried out");
        implementAuto.addActionListener(e -> implement(ExecutionMode.AUTO));
        implementManual.addActionListener(e -> implement(ExecutionMode.MANUAL));
        closeBtn.addActionListener(e -> closeHandler.run());
        statusLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
        right.add(statusLabel);
        right.add(closeBtn);
        right.add(implementManual);
        right.add(implementAuto);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(feedbackRow, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        setPlan(sessionName, plan);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setActions(Actions actions) {
        if (actions != null) this.actions = actions;
    }

    /** What Close does (the window closes itself). */
    public void setCloseHandler(Runnable handler) {
        this.closeHandler = handler != null ? handler : () -> { };
    }

    /** Shows a new or revised plan: edits, the feedback and the busy state are reset. */
    public void setPlan(String sessionName, String plan) {
        originalPlan = plan != null ? plan.strip() : "";
        unticked.clear();
        titleLabel.setText("Implementation plan");
        editor.setText(originalPlan);
        editor.setCaretPosition(0);
        feedbackField.setText("");
        editToggle.setSelected(false);
        editToggle.setText("Edit");
        cards.show(center, VIEW);
        render(originalPlan);
        rebuildSteps();
        int n = stepChecks.size();
        metaLabel.setText("Session: " + (sessionName != null ? sessionName : "") + " · " + n + " step" + (n == 1 ? "" : "s")
                + " · review it, untick steps to leave out, then implement");
        metaLabel.setForeground(UIUtils.mutedForeground());
        setBusy(false, " ");
    }

    /** While the agent revises the plan the buttons are off and the window says so. */
    public void setBusy(boolean busy, String message) {
        this.busy = busy;
        statusLabel.setText(message != null && !message.isBlank() ? message : " ");
        statusLabel.setForeground(busy ? UIUtils.ACCENT_COLOR : UIUtils.mutedForeground());
        editor.setEditable(!busy);
        feedbackField.setEnabled(!busy);
        updateButtons();
    }

    public boolean isBusy() {
        return busy;
    }

    /** The plan as it will be implemented: the agent's text, or the user's edit of it. */
    public String currentPlan() {
        return editor.getText().strip();
    }

    public boolean isEdited() {
        return !currentPlan().equals(originalPlan);
    }

    /** The plan's numbered steps, as listed. */
    public List<String> steps() {
        List<String> out = new ArrayList<>();
        for (JCheckBox c : stepChecks) out.add(c.getText());
        return out;
    }

    public List<String> selectedSteps() {
        List<String> out = new ArrayList<>();
        for (JCheckBox c : stepChecks) {
            if (c.isSelected()) out.add(c.getText());
        }
        return out;
    }

    /** Selects the step at {@code index} (0-based) or not. */
    void setStepSelected(int index, boolean selected) {
        stepChecks.get(index).setSelected(selected);
        remember(stepChecks.get(index));
        updateButtons();
    }

    /**
     * The prompt that carries the plan out. The agent already has an unchanged plan in the conversation; an edited
     * one is sent in full, and a partial selection names the steps to do.
     */
    static String implementationPrompt(String plan, boolean edited, List<String> steps, List<Integer> selected) {
        boolean partial = selected.size() < steps.size();
        if (!edited && !partial) return IMPLEMENT_PROMPT;
        StringBuilder sb = new StringBuilder(edited ? "Implement this plan (the user edited it), step by step."
                : "Implement the plan above, step by step.");
        if (partial) {
            sb.append(" Do only these steps and skip the others:");
            for (int i : selected) sb.append('\n').append(i + 1).append(". ").append(steps.get(i));
        }
        sb.append("\nMark each task in_progress when you start it and completed when it is done.");
        if (edited) sb.append("\n\n").append(plan.strip());
        return sb.toString();
    }

    static String revisionPrompt(String feedback, String plan, boolean edited) {
        StringBuilder sb = new StringBuilder("Revise the implementation plan. Feedback: ").append(feedback.strip());
        if (edited) sb.append("\n\nStart from this edited version of the plan:\n\n").append(plan.strip());
        sb.append("\n\nFinish with the full revised plan, with the steps as a numbered list.");
        return sb.toString();
    }

    void implement(ExecutionMode mode) {
        if (busy) return;
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < stepChecks.size(); i++) {
            if (stepChecks.get(i).isSelected()) selected.add(i);
        }
        if (!stepChecks.isEmpty() && selected.isEmpty()) return;
        String prompt = implementationPrompt(currentPlan(), isEdited(), steps(), selected);
        actions.implement(mode, prompt, selectedSteps());
        closeHandler.run();
    }

    void revise() {
        String feedback = feedbackField.getText();
        if (busy || feedback.isBlank()) return;
        actions.revise(revisionPrompt(feedback, currentPlan(), isEdited()));
        setBusy(true, "Revising the plan…");
    }

    private void planEdited() {
        rebuildSteps();
    }

    /** Lists the current plan's steps; a step the user unticked stays unticked while the plan is edited. */
    private void rebuildSteps() {
        stepChecks.clear();
        stepsBox.removeAll();
        List<String> steps = TaskBoard.stepsFromPlan(editor.getText());
        for (int i = 0; i < steps.size(); i++) {
            JCheckBox box = new JCheckBox(steps.get(i), !unticked.contains(steps.get(i)));
            box.setOpaque(false);
            box.setToolTipText("Step " + (i + 1) + ": untick to leave it out");
            box.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
            box.setAlignmentX(LEFT_ALIGNMENT);
            box.addActionListener(e -> {
                remember(box);
                updateButtons();
            });
            stepChecks.add(box);
            stepsBox.add(box);
        }
        if (steps.isEmpty()) {
            JLabel none = new JLabel("<html>No numbered steps in this plan: it is implemented as a whole.</html>");
            none.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
            none.setForeground(UIUtils.mutedForeground());
            stepsBox.add(none);
        }
        stepsBox.revalidate();
        stepsBox.repaint();
        updateButtons();
    }

    private void selectAll(boolean selected) {
        for (JCheckBox c : stepChecks) {
            c.setSelected(selected);
            remember(c);
        }
        updateButtons();
    }

    /** Keeps the user's choice for a step by its text, so rebuilding the list (while editing) does not reset it. */
    private void remember(JCheckBox box) {
        if (box.isSelected()) unticked.remove(box.getText());
        else unticked.add(box.getText());
    }

    private void updateButtons() {
        boolean anyStep = stepChecks.isEmpty() || stepChecks.stream().anyMatch(AbstractButton::isSelected);
        boolean hasPlan = !currentPlan().isEmpty();
        implementAuto.setEnabled(!busy && hasPlan && anyStep);
        implementManual.setEnabled(!busy && hasPlan && anyStep);
        reviseBtn.setEnabled(!busy && !feedbackField.getText().isBlank());
        String tip = !anyStep ? "Tick at least one step" : null;
        implementAuto.setToolTipText(tip != null ? tip : "Switch to Auto mode and carry out the plan");
        implementManual.setToolTipText(tip != null ? tip
                : "Switch to Manual mode: you approve each change while the plan is carried out");
    }

    /** The plan rendered like an answer in the chat: formatted text, code blocks and tables. */
    private void render(String markdown) {
        rendered.removeAll();
        for (MarkdownTableParser.Segment seg : MarkdownTableParser.parseSegments(markdown)) {
            JComponent c;
            if (seg instanceof MarkdownTableParser.CodeBlockSegment cb) {
                c = new MarkdownCodePanel(cb);
            } else if (seg instanceof MarkdownTableParser.TableSegment tab) {
                c = new MarkdownTablePanel(tab);
            } else if (seg instanceof MarkdownTableParser.TextSegment ts && !ts.text().isBlank()) {
                c = new MarkdownTextPanel(ts.text().strip());
            } else {
                continue;
            }
            c.setAlignmentX(LEFT_ALIGNMENT);
            rendered.add(c);
            rendered.add(Box.createVerticalStrut(8));
        }
        rendered.revalidate();
        rendered.repaint();
    }

    private void saveAsMarkdown() {
        JFileChooser chooser = new JFileChooser(ProjectManager.getInstance().getCurrentProjectDirectory());
        chooser.setSelectedFile(new File("implementation-plan.md"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " exists. Replace it?", "Save plan",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.writeString(file.toPath(), currentPlan() + "\n", StandardCharsets.UTF_8);
            Toast.success(this, "Plan saved to " + file.getName());
        } catch (Exception e) {
            Toast.error(this, "Could not save the plan: " + e.getMessage());
        }
    }
}

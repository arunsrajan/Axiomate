package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ProjectState;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.interop.SessionTranscriptExporter;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.components.SessionsPanel;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Browses agent sessions across every project Axiomate knows about: search all projects, preview
 * transcripts, open a project, copy sessions into the current project, export or delete them.
 */
public class SessionManagerDialog extends JDialog {

    record Row(ProjectState project, AgentSession session) {
        File dir() {
            return new File(project.getProjectPath());
        }
    }

    private final IdeActions actions;
    private final DefaultListModel<ProjectState> projectModel = new DefaultListModel<>();
    private final JList<ProjectState> projectList = new JList<>(projectModel);
    private final SessionsTableModel tableModel = new SessionsTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea preview = DialogSupport.previewArea();
    private final JTextField search = new JTextField();
    private final JLabel status = new JLabel(" ");

    public SessionManagerDialog(Window owner, IdeActions actions) {
        super(owner, "Session Manager — All Projects", ModalityType.APPLICATION_MODAL);
        this.actions = actions != null ? actions : IdeActions.NONE;
        setSize(1120, 700);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
        add(DialogSupport.banner("Agent sessions across your projects",
                "Every project keeps its own sessions. Search them all, preview a conversation, copy it into the "
                        + "current project or reopen the project it belongs to."), BorderLayout.NORTH);

        // Left: projects
        projectList.setCellRenderer(new ProjectRenderer());
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && search.getText().isBlank()) loadSelectedProject();
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(new EmptyBorder(10, 12, 0, 6));
        left.add(UIUtils.sectionHeader("Projects"), BorderLayout.NORTH);
        left.add(new JScrollPane(projectList), BorderLayout.CENTER);
        JPanel projectButtons = new JPanel(new GridLayout(1, 2, 6, 0));
        JButton openProject = new JButton("Open Project");
        openProject.addActionListener(e -> {
            ProjectState p = projectList.getSelectedValue();
            if (p != null && new File(p.getProjectPath()).isDirectory()) {
                this.actions.openProject(new File(p.getProjectPath()));
                dispose();
            }
        });
        JButton forget = new JButton("Forget");
        forget.setToolTipText("Remove this project from Axiomate's recent list (files on disk are not touched)");
        forget.addActionListener(e -> forgetProject());
        projectButtons.add(openProject);
        projectButtons.add(forget);
        left.add(projectButtons, BorderLayout.SOUTH);

        // Right: sessions + preview
        search.putClientProperty("JTextField.placeholderText", "Search sessions in all projects…");
        search.putClientProperty("JTextField.leadingIcon", UIUtils.glyph(UIUtils.Glyph.SEARCH, 14, null));
        search.putClientProperty("JTextField.showClearButton", true);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onSearch(); }
            public void removeUpdate(DocumentEvent e) { onSearch(); }
            public void changedUpdate(DocumentEvent e) { onSearch(); }
        });
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] widths = {280, 110, 190, 70, 140, 200};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showPreview();
        });

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(new EmptyBorder(10, 6, 0, 12));
        right.add(search, BorderLayout.NORTH);
        JSplitPane vsplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(preview));
        vsplit.setResizeWeight(0.45);
        vsplit.setBorder(null);
        right.add(vsplit, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.0);
        split.setDividerLocation(300);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        JButton copy = new JButton("Copy to Current Project");
        copy.addActionListener(e -> copyToCurrent());
        JButton export = new JButton("Export Markdown…");
        export.addActionListener(e -> {
            Row r = selectedRow();
            if (r != null) SessionsPanel.exportMarkdown(this, r.session());
        });
        JButton delete = new JButton("Delete Session");
        delete.addActionListener(e -> deleteSelected());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        add(DialogSupport.buttonBar(status, copy, export, delete, close), BorderLayout.SOUTH);
        DialogSupport.closeOnEscape(this);

        reloadProjects();
    }

    private boolean isCurrent(ProjectState p) {
        File cur = SessionManager.getInstance().getCurrentProjectDirectory();
        return cur != null && ProjectStateManager.normalizePath(cur).equals(p.getProjectPath());
    }

    private void reloadProjects() {
        ProjectState keep = projectList.getSelectedValue();
        projectModel.clear();
        List<ProjectState> known = new ArrayList<>(ProjectStateManager.getInstance().getKnownProjects());
        File cur = SessionManager.getInstance().getCurrentProjectDirectory();
        if (cur != null && known.stream().noneMatch(this::isCurrent)) {
            ProjectState ps = new ProjectState(ProjectStateManager.normalizePath(cur));
            ps.setProjectName(cur.getName());
            known.add(0, ps);
        }
        known.forEach(projectModel::addElement);
        int idx = 0;
        for (int i = 0; i < projectModel.size(); i++) {
            if ((keep != null && projectModel.get(i).getProjectPath().equals(keep.getProjectPath()))
                    || (keep == null && isCurrent(projectModel.get(i)))) {
                idx = i;
            }
        }
        if (!projectModel.isEmpty()) projectList.setSelectedIndex(idx);
    }

    private List<AgentSession> sessionsOf(ProjectState p) {
        return SessionManager.getInstance().getSessionsForProject(new File(p.getProjectPath()));
    }

    private void loadSelectedProject() {
        ProjectState p = projectList.getSelectedValue();
        List<Row> rows = new ArrayList<>();
        if (p != null) {
            for (AgentSession s : sessionsOf(p)) rows.add(new Row(p, s));
        }
        rows.sort(Comparator.comparing((Row r) -> r.session().getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        tableModel.setRows(rows);
        status.setText(rows.size() + " session(s) in " + (p != null ? p.getProjectName() : "—"));
        if (!rows.isEmpty()) table.setRowSelectionInterval(0, 0);
        else preview.setText("");
    }

    private void onSearch() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            loadSelectedProject();
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < projectModel.size(); i++) {
            ProjectState p = projectModel.get(i);
            for (AgentSession s : sessionsOf(p)) {
                if (matches(s, q)) rows.add(new Row(p, s));
            }
        }
        tableModel.setRows(rows);
        status.setText(rows.size() + " match(es) across " + projectModel.size() + " project(s)");
        if (!rows.isEmpty()) table.setRowSelectionInterval(0, 0);
    }

    private static boolean matches(AgentSession s, String q) {
        if (s.getName() != null && s.getName().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (AgentMessage m : s.getMessages()) {
            if (m.getContent().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private Row selectedRow() {
        int i = table.getSelectedRow();
        return i >= 0 ? tableModel.rows.get(table.convertRowIndexToModel(i)) : null;
    }

    private void showPreview() {
        Row r = selectedRow();
        if (r == null) {
            preview.setText("");
            return;
        }
        preview.setText(SessionTranscriptExporter.toMarkdown(r.session(), false));
        preview.setCaretPosition(0);
    }

    private void copyToCurrent() {
        Row r = selectedRow();
        if (r == null) return;
        File cur = SessionManager.getInstance().getCurrentProjectDirectory();
        AgentSession copy = SessionManager.getInstance().copySessionToProject(r.session(), cur);
        if (copy != null) {
            SessionManager.getInstance().switchSession(copy.getId());
            Toast.success(this, "Copied '" + r.session().getName() + "' into the current project");
        }
    }

    private void deleteSelected() {
        Row r = selectedRow();
        if (r == null) return;
        int ok = JOptionPane.showConfirmDialog(this, "Delete session '" + r.session().getName() + "' from "
                + r.project().getProjectName() + "?", "Delete Session", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        SessionManager.getInstance().deleteSessionFromProject(r.dir(), r.session().getId());
        if (search.getText().isBlank()) loadSelectedProject();
        else onSearch();
    }

    private void forgetProject() {
        ProjectState p = projectList.getSelectedValue();
        if (p == null) return;
        if (isCurrent(p)) {
            Toast.warning(this, "The open project cannot be forgotten.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Forget '" + p.getProjectName() + "'? Its saved tabs and sessions are "
                + "removed from Axiomate's index; .axiomate/sessions.json inside the project is kept.", "Forget Project",
                JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION && ProjectStateManager.getInstance().forgetProject(p.getProjectPath())) {
            reloadProjects();
        }
    }

    private class ProjectRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            ProjectState p = (ProjectState) value;
            boolean exists = new File(p.getProjectPath()).isDirectory();
            setText("<html><b>" + escape(p.getProjectName()) + (isCurrent(p) ? " (open)" : "") + "</b><br><small>"
                    + escape(shortenPath(p.getProjectPath())) + (exists ? "" : " — missing") + "</small></html>");
            setToolTipText(p.getProjectPath());
            setBorder(new EmptyBorder(4, 8, 4, 8));
            return this;
        }
    }

    /** Keeps the start and end of long paths, e.g. /home/me/…/work/payments-api. */
    private static String shortenPath(String path) {
        if (path == null || path.length() <= 44) return path;
        return path.substring(0, 14) + "…" + path.substring(path.length() - 28);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static class SessionsTableModel extends AbstractTableModel {
        private final String[] cols = {"Session", "Project", "Provider / Model", "Messages", "Last active", "Origin"};
        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> r) {
            rows = r;
            fireTableDataChanged();
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            AgentSession s = row.session();
            return switch (c) {
                case 0 -> (s.isPinned() ? "📌 " : "") + s.getName();
                case 1 -> row.project().getProjectName();
                case 2 -> s.getProviderId() + " / " + s.getModelId();
                case 3 -> s.getMessages().size();
                case 4 -> s.getUpdatedAt();
                default -> s.getOrigin();
            };
        }
    }
}

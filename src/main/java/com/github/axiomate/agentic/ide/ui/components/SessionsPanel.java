package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.session.AgentSession;
import com.github.axiomate.agentic.ide.agent.session.SessionManager;
import com.github.axiomate.agentic.ide.config.ConfigManager;
import com.github.axiomate.agentic.ide.config.ProjectState;
import com.github.axiomate.agentic.ide.config.ProjectStateManager;
import com.github.axiomate.agentic.ide.interop.SessionTranscriptExporter;
import com.github.axiomate.agentic.ide.ui.IdeActions;
import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.Toast;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.GitBranch;
import com.github.axiomate.agentic.ide.util.ProjectManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sidebar view listing agent sessions grouped by project: the open project first, then every other known project
 * with its saved sessions. Search, switch (opening another project when needed), rename, pin, duplicate, export
 * and delete sessions, and import sessions from other coding agents.
 */
public class SessionsPanel extends JPanel {

    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> list = ScrollablePanel.widthTrackingList(model);
    /** Keys (normalized paths) of collapsed project groups. */
    private final java.util.Set<String> collapsed = new java.util.HashSet<>();
    /** Sessions of the projects that are not open; rebuilt when the project changes, not on every session event. */
    private List<ProjectGroup> otherProjects = new ArrayList<>();

    /** A project heading in the list: its sessions, folder and checked-out branch. */
    record ProjectGroup(String key, File dir, String name, String branch, List<AgentSession> sessions, boolean current) {
    }
    private final JTextField searchField = new JTextField();
    private final JLabel projectLabel = new JLabel();
    private final JLabel countLabel = new JLabel();
    private final IdeActions actions;

    public SessionsPanel(IdeActions actions) {
        super(new BorderLayout());
        this.actions = actions != null ? actions : IdeActions.NONE;

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(8, 10, 4, 6));
        JPanel titles = new JPanel(new GridLayout(2, 1));
        titles.setOpaque(false);
        titles.add(UIUtils.sectionHeader("Sessions"));
        projectLabel.setFont(UIUtils.uiFont(Font.BOLD, 12.5f));
        titles.add(projectLabel);
        header.add(titles, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.PLUS, 16, null), "New session", e -> newSession()));
        JButton importBtn = UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.DOWNLOAD, 16, null), "Import sessions", null);
        importBtn.addActionListener(e -> importMenu().show(importBtn, 0, importBtn.getHeight()));
        buttons.add(importBtn);
        JButton more = UIUtils.iconButton(UIUtils.glyph(UIUtils.Glyph.MORE, 16, null), "More", null);
        more.addActionListener(e -> moreMenu().show(more, 0, more.getHeight()));
        buttons.add(more);
        header.add(buttons, BorderLayout.EAST);

        searchField.putClientProperty("JTextField.placeholderText", "Search sessions and messages…");
        searchField.putClientProperty("JTextField.leadingIcon", UIUtils.glyph(UIUtils.Glyph.SEARCH, 14, null));
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        JPanel searchWrap = new JPanel(new BorderLayout());
        searchWrap.setOpaque(false);
        searchWrap.setBorder(new EmptyBorder(2, 8, 6, 8));
        searchWrap.add(searchField, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(header, BorderLayout.NORTH);
        north.add(searchWrap, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        list.setCellRenderer(new RowRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx >= 0 && list.getCellBounds(idx, idx).contains(e.getPoint())) list.setSelectedIndex(idx);
                    openSelected();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        list.getActionMap().put("delete", new AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                deleteSelected();
            }
        });
        add(ScrollablePanel.verticalScroll(list), BorderLayout.CENTER);

        countLabel.setBorder(new EmptyBorder(4, 10, 6, 10));
        countLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        add(countLabel, BorderLayout.SOUTH);

        SessionManager.getInstance().addSessionChangeListener(() -> SwingUtilities.invokeLater(this::refresh));
        ProjectManager.getInstance().addProjectChangeListener(dir -> SwingUtilities.invokeLater(this::reloadAll));
        reloadOtherProjects();
        UIUtils.addThemeListener(this::applyColors);
        applyColors();
        refresh();
    }

    private void applyColors() {
        setBackground(UIUtils.surface(1));
        list.setBackground(UIUtils.surface(1));
        countLabel.setForeground(UIUtils.mutedForeground());
    }

    /** Re-reads the other projects' sessions, then rebuilds the list. */
    public void reloadAll() {
        reloadOtherProjects();
        refresh();
    }

    private void reloadOtherProjects() {
        File current = SessionManager.getInstance().getCurrentProjectDirectory();
        String currentKey = current != null ? ProjectStateManager.normalizePath(current) : null;
        List<ProjectGroup> groups = new ArrayList<>();
        for (ProjectState p : ProjectStateManager.getInstance().getKnownProjects()) {
            if (p.getProjectPath() == null || p.getProjectPath().equals(currentKey)) continue;
            File dir = new File(p.getProjectPath());
            if (!dir.isDirectory()) continue;
            List<AgentSession> sessions = new ArrayList<>(SessionManager.getInstance().getSessionsForProject(dir));
            if (sessions.isEmpty()) continue;
            for (AgentSession sess : sessions) {
                // sessions saved before folders were recorded still need to know where they belong
                if (sess.getProjectPath() == null || sess.getProjectPath().isBlank()) sess.setProjectPath(dir.getAbsolutePath());
            }
            String name = p.getProjectName() != null && !p.getProjectName().isBlank() ? p.getProjectName() : dir.getName();
            groups.add(new ProjectGroup(p.getProjectPath(), dir, name, GitBranch.of(dir), sortSessions(sessions), false));
        }
        otherProjects = groups;
    }

    private static List<AgentSession> sortSessions(List<AgentSession> sessions) {
        sessions.sort(Comparator.comparing(AgentSession::isPinned).reversed()
                .thenComparing(AgentSession::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return sessions;
    }

    public void refresh() {
        File dir = SessionManager.getInstance().getCurrentProjectDirectory();
        String key = dir != null ? ProjectStateManager.normalizePath(dir) : "(default)";
        ProjectGroup current = new ProjectGroup(key, dir, dir != null ? dir.getName() : "Default workspace",
                dir != null ? GitBranch.of(dir) : null,
                sortSessions(new ArrayList<>(SessionManager.getInstance().getSessions())), true);
        List<ProjectGroup> groups = new ArrayList<>();
        groups.add(current);
        groups.addAll(otherProjects);
        projectLabel.setText(groups.size() == 1 ? current.name() : groups.size() + " projects");
        projectLabel.setToolTipText(dir != null ? dir.getAbsolutePath() : null);

        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        Object selected = list.getSelectedValue();
        String keepId = selected instanceof AgentSession sel ? sel.getId() : null;
        if (keepId == null) {
            AgentSession active = SessionManager.getInstance().getActiveSession();
            keepId = active != null ? active.getId() : null;
        }

        model.clear();
        int shown = 0;
        int total = 0;
        for (ProjectGroup g : groups) {
            total += g.sessions().size();
            List<AgentSession> matching = new ArrayList<>();
            for (AgentSession sess : g.sessions()) {
                if (q.isEmpty() || matches(sess, q)) matching.add(sess);
            }
            if (!q.isEmpty() && matching.isEmpty()) continue;
            model.addElement(g);
            // a search shows every match; otherwise collapsed groups hide their sessions
            if (!q.isEmpty() || !collapsed.contains(g.key())) {
                for (AgentSession sess : matching) model.addElement(sess);
            }
            shown += matching.size();
        }
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i) instanceof AgentSession sess && sess.getId().equals(keepId)) {
                list.setSelectedIndex(i);
                break;
            }
        }
        countLabel.setText(shown + " of " + total + " session(s) in " + groups.size() + " project(s)");
    }

    /** The project group a list row belongs to (the row itself for a heading). */
    private ProjectGroup groupOf(int index) {
        for (int i = index; i >= 0; i--) {
            if (model.get(i) instanceof ProjectGroup g) return g;
        }
        return null;
    }

    private static boolean matches(AgentSession s, String q) {
        if (s.getName() != null && s.getName().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (s.getModelId().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (AgentMessage m : s.getMessages()) {
            if (m.getContent().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private void openSelected() {
        Object value = list.getSelectedValue();
        if (value instanceof ProjectGroup g) {
            if (!collapsed.remove(g.key())) collapsed.add(g.key());
            refresh();
            return;
        }
        if (!(value instanceof AgentSession s)) return;
        // Sessions of the open project switch directly; others open their project first
        if (SessionManager.getInstance().findSession(s.getId()) != null) {
            SessionManager.getInstance().switchSession(s.getId());
        }
        actions.showSessionFolder(s);
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int idx = list.locationToIndex(e.getPoint());
        if (idx < 0) return;
        list.setSelectedIndex(idx);
        ProjectGroup group = groupOf(idx);
        if (list.getSelectedValue() instanceof ProjectGroup g) {
            JPopupMenu menu = new JPopupMenu();
            if (!g.current() && g.dir() != null) menu.add(item("Open project", () -> actions.openProject(g.dir())));
            if (g.current()) menu.add(item("New session…", this::newSession));
            menu.add(item(collapsed.contains(g.key()) ? "Expand" : "Collapse", this::openSelected));
            menu.addSeparator();
            menu.add(item("Collapse all projects", () -> {
                for (int i = 0; i < model.size(); i++) if (model.get(i) instanceof ProjectGroup pg) collapsed.add(pg.key());
                refresh();
            }));
            menu.add(item("Expand all projects", () -> {
                collapsed.clear();
                refresh();
            }));
            menu.show(list, e.getX(), e.getY());
            return;
        }
        if (!(list.getSelectedValue() instanceof AgentSession s)) return;
        if (group != null && !group.current()) {
            otherProjectMenu(s, group).show(list, e.getX(), e.getY());
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Open", () -> openSelected()));
        menu.add(item("Rename…", () -> rename(s)));
        menu.add(item("Duplicate", () -> SessionManager.getInstance().duplicateSession(s.getId())));
        menu.add(item(s.isPinned() ? "Unpin" : "Pin to top", () -> SessionManager.getInstance().setPinned(s.getId(), !s.isPinned())));
        menu.addSeparator();
        menu.add(item("Export as Markdown…", () -> exportMarkdown(this, s)));
        JMenu copyTo = new JMenu("Copy to project");
        List<ProjectState> projects = ProjectStateManager.getInstance().getKnownProjects();
        File current = SessionManager.getInstance().getCurrentProjectDirectory();
        for (ProjectState p : projects) {
            File dir = new File(p.getProjectPath());
            if (current != null && ProjectStateManager.normalizePath(current).equals(p.getProjectPath())) continue;
            copyTo.add(item(p.getProjectName() + "  —  " + p.getProjectPath(), () -> {
                SessionManager.getInstance().copySessionToProject(s, dir);
                reloadAll();
                Toast.success(this, "Copied '" + s.getName() + "' to " + p.getProjectName());
            }));
        }
        copyTo.setEnabled(copyTo.getItemCount() > 0);
        menu.add(copyTo);
        menu.addSeparator();
        menu.add(item("Delete", this::deleteSelected));
        menu.show(list, e.getX(), e.getY());
    }

    /** Menu for a session of a project that is not open: actions that work without switching projects. */
    private JPopupMenu otherProjectMenu(AgentSession s, ProjectGroup group) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Open (switches to " + group.name() + ")", this::openSelected));
        menu.add(item("Export as Markdown…", () -> exportMarkdown(this, s)));
        File current = SessionManager.getInstance().getCurrentProjectDirectory();
        if (current != null) {
            menu.add(item("Copy to the open project", () -> {
                SessionManager.getInstance().copySessionToProject(s, current);
                Toast.success(this, "Copied '" + s.getName() + "' to " + current.getName());
            }));
        }
        menu.addSeparator();
        menu.add(item("Delete", this::deleteSelected));
        return menu;
    }

    private void deleteSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0 || !(list.getSelectedValue() instanceof AgentSession s)) return;
        ProjectGroup group = groupOf(idx);
        int res = JOptionPane.showConfirmDialog(this, "Delete session '" + s.getName() + "' and its " + s.getMessages().size()
                + " message(s)?", "Delete Session", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (res != JOptionPane.OK_OPTION) return;
        if (group != null && !group.current()) {
            SessionManager.getInstance().deleteSessionFromProject(group.dir(), s.getId());
            reloadAll();
        } else {
            SessionManager.getInstance().closeSession(s.getId());
        }
    }

    private void rename(AgentSession s) {
        String name = (String) JOptionPane.showInputDialog(this, "Session name:", "Rename Session",
                JOptionPane.PLAIN_MESSAGE, null, null, s.getName());
        if (name != null && !name.isBlank()) SessionManager.getInstance().renameSession(s.getId(), name.trim());
    }

    private void newSession() {
        var cfg = ConfigManager.getInstance().getConfig();
        String name = JOptionPane.showInputDialog(this, "Session name:", "Agent Session " + (SessionManager.getInstance().getSessions().size() + 1));
        if (name != null && !name.isBlank()) {
            SessionManager.getInstance().createSession(name.trim(), cfg.getActiveProviderId(), cfg.getActiveModelId(), cfg.isAutoRoutingEnabled());
        }
    }

    private JPopupMenu importMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(item("From Claude Code / Codex…", actions::openExternalSessionImport));
        m.add(item("From Axiomate JSON file…", () -> {
            JFileChooser chooser = new JFileChooser(SessionManager.getInstance().getCurrentProjectDirectory());
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    SessionManager.getInstance().importSessionsFromFile(chooser.getSelectedFile(), true);
                    Toast.success(this, "Imported sessions from " + chooser.getSelectedFile().getName());
                } catch (Exception ex) {
                    Toast.error(this, "Import failed: " + ex.getMessage());
                }
            }
        }));
        return m;
    }

    private JPopupMenu moreMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Session Manager (all projects)…", actions::openSessionManager));
        m.add(item("Export all sessions to JSON…", () -> {
            JFileChooser chooser = new JFileChooser(SessionManager.getInstance().getCurrentProjectDirectory());
            chooser.setSelectedFile(new File("agent-sessions.json"));
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    SessionManager.getInstance().exportSessionsToFile(chooser.getSelectedFile());
                    Toast.success(this, "Exported sessions to " + chooser.getSelectedFile().getName());
                } catch (Exception ex) {
                    Toast.error(this, "Export failed: " + ex.getMessage());
                }
            }
        }));
        m.add(item("Reload from disk", () -> {
            SessionManager.getInstance().loadSessionsForProject(SessionManager.getInstance().getCurrentProjectDirectory());
            reloadAll();
        }));
        return m;
    }

    static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.addActionListener(e -> r.run());
        return i;
    }

    /**
     * Saves a session transcript as Markdown chosen via a file dialog.
     */
    public static void exportMarkdown(Component parent, AgentSession s) {
        JFileChooser chooser = new JFileChooser(SessionManager.getInstance().getCurrentProjectDirectory());
        String safe = s.getName().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-");
        chooser.setSelectedFile(new File(safe + ".md"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        int opt = JOptionPane.showConfirmDialog(parent, "Include tool outputs in the transcript?", "Export Transcript",
                JOptionPane.YES_NO_CANCEL_OPTION);
        if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) return;
        try {
            Files.writeString(chooser.getSelectedFile().toPath(),
                    SessionTranscriptExporter.toMarkdown(s, opt == JOptionPane.YES_OPTION), StandardCharsets.UTF_8);
            Toast.success(parent, "Transcript saved to " + chooser.getSelectedFile().getName());
        } catch (Exception ex) {
            Toast.error(parent, "Could not save transcript: " + ex.getMessage());
        }
    }

    /**
     * Two-line session row: name (with pin / active markers) and provider · model · messages · last active.
     */
    /** "14:32" for today, "Sep 27" this year, otherwise the date. */
    static String friendlyTime(String timestamp) {
        if (timestamp == null || timestamp.length() < 16) return timestamp == null ? "" : timestamp;
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(timestamp.replace(' ', 'T'));
            java.time.LocalDate today = java.time.LocalDate.now();
            if (t.toLocalDate().equals(today)) return t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            if (t.getYear() == today.getYear()) return t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH));
            return t.toLocalDate().toString();
        } catch (Exception e) {
            return timestamp;
        }
    }

    /** Project headings and session rows. */
    class RowRenderer implements ListCellRenderer<Object> {
        private final SessionCellRenderer sessionRow = new SessionCellRenderer();
        private final ProjectHeaderRenderer header = new ProjectHeaderRenderer();

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                      boolean cellHasFocus) {
            @SuppressWarnings("unchecked")
            JList<Object> l = (JList<Object>) list;
            if (value instanceof ProjectGroup g) {
                // a search shows every match, so groups count as expanded while searching
                boolean expanded = !collapsed.contains(g.key()) || !searchField.getText().isBlank();
                return header.render(l, g, isSelected, expanded);
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            Component c = sessionRow.getListCellRendererComponent((JList) l, (AgentSession) value, index, isSelected, cellHasFocus);
            return c;
        }
    }

    /** "⌄ 📁 payments-api   ⎇ main            3" */
    static class ProjectHeaderRenderer extends JPanel {
        private final JLabel name = new JLabel();
        private final JLabel branch = new JLabel();
        private final JLabel count = new JLabel();

        ProjectHeaderRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(7, 6, 5, 10));
            name.setIconTextGap(6);
            branch.setIconTextGap(3);
            // the branch takes the remaining width and is shortened with "…" when it does not fit
            add(name, BorderLayout.WEST);
            add(branch, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);
        }

        Component render(JList<Object> list, ProjectGroup g, boolean selected, boolean open) {
            Color fg = selected ? list.getSelectionForeground() : UIUtils.foreground();
            Color muted = selected ? list.getSelectionForeground() : UIUtils.mutedForeground();
            name.setIcon(UIUtils.glyph(open ? UIUtils.Glyph.CHEVRON_DOWN : UIUtils.Glyph.CHEVRON_RIGHT, 14, muted));
            name.setText(g.name());
            name.setFont(UIUtils.uiFont(Font.BOLD, 12f));
            // the open project is shown in the accent colour
            name.setForeground(g.current() && !selected ? UIUtils.accentText(UIUtils.ACCENT_COLOR) : fg);
            branch.setText(g.branch() != null ? g.branch() : "");
            branch.setIcon(g.branch() != null ? UIUtils.glyph(UIUtils.Glyph.BRANCH, 12, muted) : null);
            branch.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
            branch.setForeground(muted);
            count.setText(String.valueOf(g.sessions().size()));
            count.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
            count.setForeground(muted);
            setBackground(selected ? list.getSelectionBackground() : UIUtils.surface(2));
            setToolTipText(g.dir() != null ? g.dir().getAbsolutePath() + (g.branch() != null ? "  (branch " + g.branch() + ")" : "")
                    + (g.current() ? "  — open project" : "") : null);
            return this;
        }
    }

    static class SessionCellRenderer extends JPanel implements ListCellRenderer<AgentSession> {
        private final JLabel name = new JLabel();
        private final JLabel meta = new JLabel();
        private boolean active;

        SessionCellRenderer() {
            super(new GridLayout(2, 1, 0, 1));
            setBorder(new EmptyBorder(6, 28, 6, 8)); // indented under its project heading
            add(name);
            add(meta);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends AgentSession> list, AgentSession s, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            AgentSession activeSession = SessionManager.getInstance().getActiveSession();
            active = activeSession != null && activeSession.getId().equals(s.getId());
            name.setText((s.isPinned() ? "📌 " : "") + s.getName());
            name.setFont(UIUtils.uiFont(active ? Font.BOLD : Font.PLAIN, 12.5f));
            meta.setText(friendlyTime(s.getUpdatedAt()) + " · " + s.getMessages().size() + " msgs · " + s.getModelId());
            meta.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
            setToolTipText("<html><b>" + s.getName() + "</b><br>" + s.getProviderId() + " / " + s.getModelId()
                    + "<br>Last active " + s.getUpdatedAt() + (s.getOrigin().isBlank() ? "" : "<br>" + s.getOrigin()) + "</html>");
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                name.setForeground(list.getSelectionForeground());
                meta.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                name.setForeground(UIUtils.foreground());
                meta.setForeground(UIUtils.mutedForeground());
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (active) {
                g.setColor(UIUtils.ACCENT_COLOR);
                g.fillRect(14, 4, 3, getHeight() - 8);
            }
        }
    }
}

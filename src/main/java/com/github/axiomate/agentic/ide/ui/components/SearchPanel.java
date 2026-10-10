package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import com.github.axiomate.agentic.ide.util.WorkspaceSearch;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.PatternSyntaxException;

/**
 * Find in Files (Ctrl+Shift+F): searches the open project and lists matching lines grouped by file; double-click or
 * Enter opens the file with the match selected.
 */
public class SearchPanel extends JPanel {

    /** Opens a search result in the editor. */
    public interface OpenHandler {
        void open(File file, int line, int column, int length);
    }

    /**
     * One list row: a file heading ({@code match == null}) or a matching line.
     */
    public record Row(String relativePath, int count, WorkspaceSearch.Match match) {
        boolean isHeader() {
            return match == null;
        }
    }

    static final int MAX_RESULTS = 2_000;

    final JTextField queryField = new JTextField();
    final JTextField includeField = new JTextField(14);
    final JToggleButton matchCase = toggle("Aa", "Match case");
    final JToggleButton wholeWord = toggle("W", "Whole word");
    final JToggleButton regex = toggle(".*", "Regular expression");
    private final JLabel status = new JLabel("Type a search and press Enter");
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private OpenHandler openHandler = (f, l, c, len) -> { };
    private AtomicBoolean currentCancel = new AtomicBoolean();
    private volatile SwingWorker<WorkspaceSearch.Result, Void> worker;

    public SearchPanel() {
        super(new BorderLayout(0, 4));
        setBorder(new EmptyBorder(4, 6, 4, 6));

        queryField.putClientProperty("JTextField.placeholderText", "Search in project files");
        queryField.putClientProperty("JTextField.leadingIcon", UIUtils.glyph(UIUtils.Glyph.SEARCH, 14, null));
        includeField.putClientProperty("JTextField.placeholderText", "Files, e.g. *.java");
        includeField.setToolTipText("Comma-separated globs: *.java, src/**/*.ts — empty searches every file");
        queryField.addActionListener(e -> search());
        includeField.addActionListener(e -> search());
        for (JToggleButton b : List.of(matchCase, wholeWord, regex)) b.addActionListener(e -> {
            if (!queryField.getText().isEmpty()) search();
        });

        JButton searchBtn = new JButton("Search");
        searchBtn.addActionListener(e -> search());
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            cancel();
            model.clear();
            status.setText(" ");
        });

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.setOpaque(false);
        options.add(matchCase);
        options.add(wholeWord);
        options.add(regex);
        options.add(includeField);
        options.add(searchBtn);
        options.add(clearBtn);

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.add(queryField, BorderLayout.CENTER);
        top.add(options, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        list.setCellRenderer(new RowRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openSelected();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-result");
        list.getActionMap().put("open-result", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        add(ScrollablePanel.verticalScroll(list), BorderLayout.CENTER);

        status.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        status.setForeground(UIUtils.mutedForeground());
        add(status, BorderLayout.SOUTH);
    }

    public void setOpenHandler(OpenHandler handler) {
        this.openHandler = handler != null ? handler : (f, l, c, len) -> { };
    }

    /** Focuses the query field, filled with {@code initialQuery} when it is a one-line text, and searches it. */
    public void focusSearch(String initialQuery) {
        if (initialQuery != null && !initialQuery.isBlank() && !initialQuery.contains("\n")) {
            queryField.setText(initialQuery);
            search();
        }
        queryField.requestFocusInWindow();
        queryField.selectAll();
    }

    public boolean isSearching() {
        SwingWorker<?, ?> w = worker;
        return w != null && !w.isDone();
    }

    /** The rows currently listed (file headings and matching lines). */
    public List<Row> getRows() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) rows.add(model.get(i));
        return rows;
    }

    /** Starts a search of the open project; a search still running is cancelled. */
    public void search() {
        String query = queryField.getText();
        File project = ProjectManager.getInstance().getCurrentProjectDirectory();
        cancel();
        if (query.isEmpty()) {
            status.setText("Type a search and press Enter");
            return;
        }
        if (project == null || !project.isDirectory()) {
            status.setText("No project folder is open");
            return;
        }
        WorkspaceSearch.Options options = new WorkspaceSearch.Options(query, regex.isSelected(), matchCase.isSelected(),
                wholeWord.isSelected(), includeField.getText(), MAX_RESULTS);
        try {
            WorkspaceSearch.compile(options);
        } catch (PatternSyntaxException e) {
            status.setText("Invalid regular expression: " + e.getDescription());
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        currentCancel = cancelled;
        status.setText("Searching…");
        SwingWorker<WorkspaceSearch.Result, Void> w = new SwingWorker<>() {
            @Override
            protected WorkspaceSearch.Result doInBackground() throws Exception {
                return WorkspaceSearch.search(project.toPath(), options, cancelled::get);
            }

            @Override
            protected void done() {
                if (cancelled.get()) return;
                try {
                    show(get());
                } catch (Exception e) {
                    model.clear();
                    status.setText("Search failed: " + e.getMessage());
                }
            }
        };
        worker = w;
        w.execute();
    }

    private void cancel() {
        currentCancel.set(true);
    }

    private void show(WorkspaceSearch.Result result) {
        model.clear();
        for (Row r : rows(result)) model.addElement(r);
        if (result.matches().isEmpty()) {
            status.setText("No results (" + result.filesSearched() + " files searched)");
            return;
        }
        status.setText(result.matches().size() + " result" + (result.matches().size() == 1 ? "" : "s") + " in "
                + result.filesMatched() + " file" + (result.filesMatched() == 1 ? "" : "s")
                + " (" + result.filesSearched() + " searched)"
                + (result.truncated() ? " — stopped at " + MAX_RESULTS + ", narrow the search to see more" : ""));
        if (model.size() > 1) list.setSelectedIndex(1);
    }

    /** Results grouped under one heading per file, in search order. */
    static List<Row> rows(WorkspaceSearch.Result result) {
        List<Row> rows = new ArrayList<>();
        List<WorkspaceSearch.Match> matches = result.matches();
        for (int i = 0; i < matches.size(); ) {
            String path = matches.get(i).relativePath();
            int j = i;
            while (j < matches.size() && matches.get(j).relativePath().equals(path)) j++;
            rows.add(new Row(path, j - i, null));
            for (int k = i; k < j; k++) rows.add(new Row(path, 0, matches.get(k)));
            i = j;
        }
        return rows;
    }

    private void openSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0) return;
        Row row = model.get(idx);
        if (row.isHeader() && idx + 1 < model.size()) row = model.get(idx + 1);
        WorkspaceSearch.Match m = row.match();
        if (m != null) openHandler.open(m.file().toFile(), m.line(), m.column(), m.length());
    }

    private static JToggleButton toggle(String text, String tip) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 5, 1, 5));
        return b;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                      boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            Row row = (Row) value;
            if (row.isHeader()) {
                setText("<html><b>" + escape(row.relativePath()) + "</b>&nbsp;&nbsp;<span style='color:gray'>"
                        + row.count() + "</span></html>");
                setBorder(new EmptyBorder(4, 4, 1, 4));
            } else {
                WorkspaceSearch.Match m = row.match();
                String line = m.lineText();
                int start = Math.min(m.column(), line.length());
                int end = Math.min(line.length(), start + m.length());
                // Leading indentation is noise in a result list
                int lead = 0;
                while (lead < start && Character.isWhitespace(line.charAt(lead))) lead++;
                setText("<html><span style='color:gray'>" + m.line() + ":</span>&nbsp;"
                        + escape(line.substring(lead, start)) + "<b>" + escape(line.substring(start, end)) + "</b>"
                        + escape(line.substring(end)) + "</html>");
                setBorder(new EmptyBorder(1, 22, 1, 4));
            }
            setFont(UIUtils.uiFont(Font.PLAIN, 12f));
            return this;
        }
    }
}

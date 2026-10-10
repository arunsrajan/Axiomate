package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.WorkspaceSearch;
import org.fife.ui.rsyntaxtextarea.DocumentRange;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Find / Replace bar above the editor (Ctrl+F, Ctrl+H): match case, whole word and regex options, every match
 * highlighted, "3 of 12" position, Enter / Shift+Enter or F3 / Shift+F3 to step, Replace and Replace All.
 */
public class FindReplaceBar extends JPanel {

    private final Supplier<RSyntaxTextArea> editor;
    final JTextField findField = new JTextField(24);
    final JTextField replaceField = new JTextField(24);
    final JToggleButton matchCase = toggle("Aa", "Match case");
    final JToggleButton wholeWord = toggle("W", "Whole word");
    final JToggleButton regex = toggle(".*", "Regular expression");
    final JLabel status = new JLabel(" ");
    private final JPanel replaceRow;
    private RSyntaxTextArea highlighted;

    public FindReplaceBar(Supplier<RSyntaxTextArea> editor) {
        super(new BorderLayout());
        this.editor = editor;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIUtils.borderColor()),
                new EmptyBorder(3, 6, 3, 6)));

        findField.putClientProperty("JTextField.placeholderText", "Find");
        replaceField.putClientProperty("JTextField.placeholderText", "Replace");
        status.setForeground(UIUtils.mutedForeground());
        status.setFont(UIUtils.uiFont(Font.PLAIN, 11f));

        JPanel findRow = row();
        findRow.add(findField);
        findRow.add(matchCase);
        findRow.add(wholeWord);
        findRow.add(regex);
        findRow.add(button("↑", "Previous match (Shift+Enter, Shift+F3)", this::findPrevious));
        findRow.add(button("↓", "Next match (Enter, F3)", this::findNext));
        findRow.add(status);

        replaceRow = row();
        replaceRow.add(replaceField);
        replaceRow.add(button("Replace", "Replace this match (Enter)", this::replaceCurrent));
        replaceRow.add(button("Replace All", "Replace every match", this::replaceAll));

        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 2));
        rows.setOpaque(false);
        rows.add(findRow);
        rows.add(replaceRow);
        add(rows, BorderLayout.CENTER);
        JButton close = button("×", "Close (Esc)", this::close);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        east.setOpaque(false);
        east.add(close);
        add(east, BorderLayout.EAST);

        findField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onQueryChanged(); }
            public void removeUpdate(DocumentEvent e) { onQueryChanged(); }
            public void changedUpdate(DocumentEvent e) { onQueryChanged(); }
        });
        for (JToggleButton b : List.of(matchCase, wholeWord, regex)) b.addActionListener(e -> onQueryChanged());
        bindKey(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find-next", this::findNext);
        bindKey(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "find-prev", this::findPrevious);
        bindKey(replaceField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "replace", this::replaceCurrent);
        for (JComponent c : List.of(findField, replaceField, this)) {
            bindKey(c, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find-close", this::close);
        }
        setVisible(false);
    }

    /** Shows the bar (with the replace row when {@code replace}), filled with the editor's one-line selection. */
    public void open(boolean replace) {
        replaceRow.setVisible(replace);
        setVisible(true);
        RSyntaxTextArea ed = editor.get();
        String sel = ed != null ? ed.getSelectedText() : null;
        if (sel != null && !sel.isEmpty() && !sel.contains("\n")) {
            findField.setText(regex.isSelected() ? Pattern.quote(sel) : sel);
        }
        revalidate();
        findField.requestFocusInWindow();
        findField.selectAll();
        refresh();
    }

    public void close() {
        setVisible(false);
        clearHighlights();
        RSyntaxTextArea ed = editor.get();
        if (ed != null) ed.requestFocusInWindow();
    }

    public boolean isReplaceVisible() {
        return isVisible() && replaceRow.isVisible();
    }

    /** Selects the next match after the caret, wrapping at the end; false when there is none. */
    public boolean findNext() {
        return step(true);
    }

    public boolean findPrevious() {
        return step(false);
    }

    /** Replaces the selected match (or finds the next one first), then moves to the next match. */
    public boolean replaceCurrent() {
        RSyntaxTextArea ed = editor.get();
        Pattern p = pattern();
        if (ed == null || p == null) return false;
        String text = ed.getText();
        int[] current = matchAt(text, p, ed.getSelectionStart(), ed.getSelectionEnd());
        if (current == null) return findNext();
        String replacement = replacement(text, p, current[0]);
        ed.replaceRange(replacement, current[0], current[1]);
        ed.setCaretPosition(current[0] + replacement.length());
        findNext();
        return true;
    }

    /** Replaces every match in one undoable edit; returns how many were replaced. */
    public int replaceAll() {
        RSyntaxTextArea ed = editor.get();
        Pattern p = pattern();
        if (ed == null || p == null) return 0;
        String text = ed.getText();
        List<int[]> ranges = findAll(text, p);
        if (ranges.isEmpty()) {
            status.setText("No results");
            return 0;
        }
        List<String> replacements = new ArrayList<>();
        for (int[] r : ranges) replacements.add(replacement(text, p, r[0]));
        ed.beginAtomicEdit();
        try {
            for (int i = ranges.size() - 1; i >= 0; i--) {
                ed.replaceRange(replacements.get(i), ranges.get(i)[0], ranges.get(i)[1]);
            }
        } finally {
            ed.endAtomicEdit();
        }
        refresh();
        status.setText("Replaced " + ranges.size());
        return ranges.size();
    }

    /** Re-highlights matches, e.g. after switching tabs. */
    public void refresh() {
        if (!isVisible()) return;
        RSyntaxTextArea ed = editor.get();
        if (highlighted != null && highlighted != ed) highlighted.clearMarkAllHighlights();
        highlighted = ed;
        if (ed == null) {
            status.setText(" ");
            return;
        }
        Pattern p;
        try {
            p = compile();
        } catch (PatternSyntaxException e) {
            ed.clearMarkAllHighlights();
            status.setText("Invalid regex");
            status.setForeground(UIUtils.ERROR_COLOR);
            return;
        }
        status.setForeground(UIUtils.mutedForeground());
        if (p == null) {
            ed.clearMarkAllHighlights();
            status.setText(" ");
            return;
        }
        List<int[]> ranges = findAll(ed.getText(), p);
        List<DocumentRange> marks = new ArrayList<>();
        for (int[] r : ranges) marks.add(new DocumentRange(r[0], r[1]));
        ed.markAll(marks);
        int idx = indexOf(ranges, ed.getSelectionStart(), ed.getSelectionEnd());
        status.setText(ranges.isEmpty() ? "No results"
                : idx >= 0 ? (idx + 1) + " of " + ranges.size() : ranges.size() + " match" + (ranges.size() == 1 ? "" : "es"));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** Every non-empty match as {start, end}. */
    static List<int[]> findAll(String text, Pattern p) {
        List<int[]> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            if (m.end() > m.start()) out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    /** The match to select stepping from the selection [selStart, selEnd), wrapping around; null when none. */
    static int[] next(List<int[]> ranges, int selStart, int selEnd, boolean forward) {
        if (ranges.isEmpty()) return null;
        if (forward) {
            // With a match selected this is the one after it; with only a caret, the first at or after the caret
            for (int[] r : ranges) {
                if (r[0] >= selEnd) return r;
            }
            return ranges.get(0);
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            if (ranges.get(i)[0] < selStart) return ranges.get(i);
        }
        return ranges.get(ranges.size() - 1);
    }

    private void onQueryChanged() {
        // Incremental search: jump to the first match at or after where the selection starts, wrapping around
        RSyntaxTextArea ed = editor.get();
        Pattern p = pattern();
        if (ed != null && p != null) {
            List<int[]> ranges = findAll(ed.getText(), p);
            int from = ed.getSelectionStart();
            int[] target = ranges.isEmpty() ? null : ranges.get(0);
            for (int[] r : ranges) {
                if (r[0] >= from) {
                    target = r;
                    break;
                }
            }
            if (target != null) select(ed, target);
        }
        refresh();
    }

    private boolean step(boolean forward) {
        RSyntaxTextArea ed = editor.get();
        Pattern p = pattern();
        if (ed == null || p == null) {
            refresh();
            return false;
        }
        int[] r = next(findAll(ed.getText(), p), ed.getSelectionStart(), ed.getSelectionEnd(), forward);
        if (r != null) select(ed, r);
        refresh();
        return r != null;
    }

    private static void select(RSyntaxTextArea ed, int[] r) {
        ed.setCaretPosition(r[0]);
        ed.moveCaretPosition(r[1]);
    }

    private static int[] matchAt(String text, Pattern p, int start, int end) {
        if (end <= start) return null;
        for (int[] r : findAll(text, p)) {
            if (r[0] == start && r[1] == end) return r;
        }
        return null;
    }

    private static int indexOf(List<int[]> ranges, int start, int end) {
        for (int i = 0; i < ranges.size(); i++) {
            if (ranges.get(i)[0] == start && ranges.get(i)[1] == end) return i;
        }
        return -1;
    }

    /** The replacement for the match starting at {@code start}: "$1" and "\n" expand in regex mode. */
    private String replacement(String text, Pattern p, int start) {
        String template = replaceField.getText();
        if (!regex.isSelected()) return template;
        Matcher m = p.matcher(text);
        if (!m.find(start)) return template;
        return SearchEngine.getReplacementText(m, template);
    }

    /** The current query's pattern, or null when the query is empty or not a valid regex. */
    Pattern pattern() {
        try {
            return compile();
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private Pattern compile() {
        String q = findField.getText();
        if (q.isEmpty()) return null;
        return WorkspaceSearch.compile(new WorkspaceSearch.Options(q, regex.isSelected(), matchCase.isSelected(),
                wholeWord.isSelected(), "", 0));
    }

    private void clearHighlights() {
        if (highlighted != null) highlighted.clearMarkAllHighlights();
        highlighted = null;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        return p;
    }

    private static JToggleButton toggle(String text, String tip) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 5, 1, 5));
        return b;
    }

    private static JButton button(String text, String tip, Runnable action) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 6, 1, 6));
        b.addActionListener(e -> action.run());
        return b;
    }

    private static void bindKey(JComponent c, KeyStroke ks, String name, Runnable r) {
        c.getInputMap(c instanceof JTextField ? JComponent.WHEN_FOCUSED : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(ks, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }
}

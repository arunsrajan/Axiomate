package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;
import com.github.axiomate.agentic.ide.util.WorkspaceSearch;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quick Open (Ctrl+P): fuzzy-find a project file by name or path and open it. "App.java:42" opens at line 42,
 * ":42" goes to line 42 of the current file.
 */
public class QuickOpenDialog extends JDialog {

    /** Opens a file; {@code line} is 1-based, or -1 to keep the file's own position. */
    public interface OpenHandler {
        void open(File file, int line, int column);
    }

    /**
     * What was typed: the file part and an optional ":line[:column]".
     *
     * @param line 1-based, -1 when none was given
     */
    public record Query(String name, int line, int column) {
    }

    static final int MAX_FILES = 50_000;
    static final int MAX_SHOWN = 200;
    private static final Pattern LINE_SUFFIX = Pattern.compile("^(.*?)(?::(\\d+)(?::(\\d+))?)?\\s*$");

    private final Path root;
    private final OpenHandler openHandler;
    private final java.util.function.IntConsumer goToLine;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = ScrollablePanel.widthTrackingList(model);
    private final JTextField input = new JTextField();
    private final JLabel hint = new JLabel("Indexing project files…");
    private volatile List<String> files = List.of();

    /**
     * @param goToLine called with a line number for ":42" queries (the current file)
     */
    public QuickOpenDialog(Window owner, File projectDir, OpenHandler openHandler, java.util.function.IntConsumer goToLine) {
        super(owner, ModalityType.MODELESS);
        this.root = projectDir.toPath();
        this.openHandler = openHandler;
        this.goToLine = goToLine;
        setUndecorated(true);
        setSize(640, 420);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIUtils.borderColor()),
                new EmptyBorder(8, 8, 8, 8)));
        input.putClientProperty("JTextField.placeholderText", "Go to file… (add :line, or type :line for the current file)");
        input.putClientProperty("JTextField.leadingIcon", UIUtils.glyph(UIUtils.Glyph.SEARCH, 16, null));
        input.setFont(UIUtils.uiFont(Font.PLAIN, 14f));
        content.add(input, BorderLayout.NORTH);

        list.setCellRenderer(new PathRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openSelected();
            }
        });
        content.add(ScrollablePanel.verticalScroll(list), BorderLayout.CENTER);
        hint.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        hint.setForeground(UIUtils.mutedForeground());
        content.add(hint, BorderLayout.SOUTH);
        setContentPane(content);

        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> move(1);
                    case KeyEvent.VK_UP -> move(-1);
                    case KeyEvent.VK_PAGE_DOWN -> move(8);
                    case KeyEvent.VK_PAGE_UP -> move(-8);
                    case KeyEvent.VK_ENTER -> openSelected();
                    case KeyEvent.VK_ESCAPE -> dispose();
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                dispose();
            }
        });

        if (owner != null && owner.isShowing()) {
            Point p = owner.getLocationOnScreen();
            setLocation(p.x + (owner.getWidth() - getWidth()) / 2, p.y + 80);
        } else {
            setLocationRelativeTo(null);
        }
        index();
    }

    /** Splits "src/App.java:42:7" into name "src/App.java", line 42 and 0-based column 6. */
    public static Query parse(String text) {
        Matcher m = LINE_SUFFIX.matcher(text == null ? "" : text.trim());
        if (!m.matches() || m.group(2) == null) return new Query(text == null ? "" : text.trim(), -1, 0);
        try {
            int line = Integer.parseInt(m.group(2));
            int col = m.group(3) != null ? Math.max(0, Integer.parseInt(m.group(3)) - 1) : 0;
            return new Query(m.group(1).trim(), line, col);
        } catch (NumberFormatException e) {
            return new Query(text.trim(), -1, 0);
        }
    }

    /** Files for the list: the best matches for {@code query}, at most {@link #MAX_SHOWN}. */
    static List<String> matches(List<String> files, String query) {
        return WorkspaceSearch.rankFiles(files, parse(query).name(), MAX_SHOWN);
    }

    private void index() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return WorkspaceSearch.listFiles(root, MAX_FILES);
            }

            @Override
            protected void done() {
                try {
                    files = get();
                } catch (Exception e) {
                    files = List.of();
                }
                filter();
            }
        }.execute();
    }

    private void filter() {
        Query q = parse(input.getText());
        model.clear();
        if (q.name().isEmpty() && q.line() > 0) {
            hint.setText("Enter goes to line " + q.line() + " of the current file · Esc close");
            return;
        }
        for (String f : matches(files, input.getText())) model.addElement(f);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        hint.setText(files.isEmpty() ? "Indexing project files…"
                : model.isEmpty() ? "No matching files · Esc close"
                : "↑↓ navigate · Enter open · name:line opens at a line · Esc close");
    }

    private void move(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void openSelected() {
        Query q = parse(input.getText());
        if (q.name().isEmpty() && q.line() > 0) {
            dispose();
            SwingUtilities.invokeLater(() -> goToLine.accept(q.line()));
            return;
        }
        String rel = list.getSelectedValue();
        if (rel == null) return;
        dispose();
        File file = root.resolve(rel).toFile();
        SwingUtilities.invokeLater(() -> openHandler.open(file, q.line(), q.column()));
    }

    private static class PathRenderer extends JPanel implements ListCellRenderer<String> {
        private final JLabel name = new JLabel();
        private final JLabel folder = new JLabel();

        PathRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(5, 10, 5, 10));
            add(name, BorderLayout.WEST);
            add(folder, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            int slash = value.lastIndexOf('/');
            name.setText(value.substring(slash + 1));
            folder.setText(slash > 0 ? value.substring(0, slash) : "");
            name.setFont(UIUtils.uiFont(Font.PLAIN, 13f));
            folder.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(isSelected ? list.getSelectionForeground() : UIUtils.foreground());
            folder.setForeground(isSelected ? list.getSelectionForeground() : UIUtils.mutedForeground());
            return this;
        }
    }
}

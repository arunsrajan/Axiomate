package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.ui.util.ScrollablePanel;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fuzzy-search command palette. Entries are harvested from the menu bar and supplied by the IDE
 * (sessions, projects, sidebar views, slash commands).
 */
public class CommandPalette extends JDialog {

    /**
     * A runnable palette entry.
     *
     * @param category shown right-aligned, e.g. "Menu", "Session", "Project"
     */
    public record Entry(String label, String category, String hint, Runnable action) {
    }

    private final List<Entry> entries;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = ScrollablePanel.widthTrackingList(model);
    private final JTextField input = new JTextField();

    public CommandPalette(Window owner, List<Entry> entries) {
        super(owner, ModalityType.MODELESS);
        this.entries = entries;
        setUndecorated(true);
        setSize(640, 420);

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIUtils.borderColor()),
                new EmptyBorder(8, 8, 8, 8)));
        input.putClientProperty("JTextField.placeholderText", "Type a command, session, project or /slash command…");
        input.putClientProperty("JTextField.leadingIcon", UIUtils.glyph(UIUtils.Glyph.COMMAND, 16, null));
        input.setFont(UIUtils.uiFont(Font.PLAIN, 14f));
        root.add(input, BorderLayout.NORTH);

        list.setCellRenderer(new EntryRenderer());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) runSelected();
            }
        });
        root.add(ScrollablePanel.verticalScroll(list), BorderLayout.CENTER);
        JLabel hint = new JLabel("↑↓ navigate · Enter run · Esc close");
        hint.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        hint.setForeground(UIUtils.mutedForeground());
        root.add(hint, BorderLayout.SOUTH);
        setContentPane(root);

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
                    case KeyEvent.VK_ENTER -> runSelected();
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

        if (owner != null) {
            Point p = owner.getLocationOnScreen();
            setLocation(p.x + (owner.getWidth() - getWidth()) / 2, p.y + 80);
        } else {
            setLocationRelativeTo(null);
        }
        filter();
    }

    private void move(int delta) {
        if (model.isEmpty()) return;
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void runSelected() {
        Entry e = list.getSelectedValue();
        if (e == null) return;
        dispose();
        SwingUtilities.invokeLater(e.action());
    }

    private void filter() {
        String q = input.getText().trim().toLowerCase(Locale.ROOT);
        record Scored(Entry e, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Entry e : entries) {
            int s = score(q, (e.label() + " " + e.category()).toLowerCase(Locale.ROOT));
            if (s >= 0) scored.add(new Scored(e, s));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        model.clear();
        for (int i = 0; i < scored.size() && i < 200; i++) model.addElement(scored.get(i).e());
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    /**
     * Subsequence fuzzy match: -1 when not all query characters appear in order; higher is better.
     */
    public static int score(String query, String text) {
        if (query.isEmpty()) return 0;
        int idx = text.indexOf(query);
        if (idx >= 0) return 1000 - idx * 2 - text.length() / 10;
        int score = 0;
        int ti = 0;
        int streak = 0;
        for (char qc : query.toCharArray()) {
            if (qc == ' ') continue;
            int found = text.indexOf(qc, ti);
            if (found < 0) return -1;
            streak = found == ti ? streak + 1 : 0;
            score += 10 + streak * 5 - Math.min(9, found - ti);
            if (found == 0 || " /›-_:".indexOf(text.charAt(found - 1)) >= 0) score += 8;
            ti = found + 1;
        }
        return score;
    }

    /**
     * Collects every enabled menu item as a palette entry labelled with its menu path.
     */
    public static List<Entry> fromMenuBar(JMenuBar bar) {
        List<Entry> out = new ArrayList<>();
        if (bar == null) return out;
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu m = bar.getMenu(i);
            if (m != null) collect(m, m.getText(), out);
        }
        return out;
    }

    private static void collect(JMenu menu, String path, List<Entry> out) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item == null || !item.isEnabled()) continue;
            String label = item.getText() == null ? "" : item.getText().replaceAll("[\\p{So}\\p{Cn}\\x{FE0F}]", "").trim();
            if (item instanceof JMenu sub) {
                collect(sub, path + " › " + label, out);
            } else {
                KeyStroke ks = item.getAccelerator();
                String hint = null;
                if (ks != null) {
                    String mods = KeyEvent.getModifiersExText(ks.getModifiers());
                    hint = (mods.isEmpty() ? "" : mods + "+") + KeyEvent.getKeyText(ks.getKeyCode());
                }
                out.add(new Entry(path + " › " + label, "Menu", hint, item::doClick));
            }
        }
    }

    private static class EntryRenderer extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel label = new JLabel();
        private final JLabel right = new JLabel();

        EntryRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(5, 10, 5, 10));
            add(label, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> list, Entry value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            label.setText(value.label());
            label.setFont(UIUtils.uiFont(Font.PLAIN, 13f));
            right.setText(value.hint() != null ? value.hint() + "   " + value.category() : value.category());
            right.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : UIUtils.foreground());
            right.setForeground(isSelected ? list.getSelectionForeground() : UIUtils.mutedForeground());
            return this;
        }
    }
}

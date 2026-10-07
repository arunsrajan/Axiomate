package com.github.axiomate.agentic.ide.ui.dialogs;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Shared layout helpers for Axiomate dialogs.
 */
final class DialogSupport {

    private DialogSupport() {
    }

    /** Title + subtitle banner for the top of a dialog. */
    static JComponent banner(String title, String subtitle) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(new EmptyBorder(14, 16, 10, 16));
        p.setBackground(UIUtils.surface(2));
        JLabel t = new JLabel(title);
        t.setFont(UIUtils.uiFont(Font.BOLD, 16f));
        JLabel s = new JLabel("<html>" + subtitle + "</html>");
        s.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        s.setForeground(UIUtils.mutedForeground());
        p.add(t, BorderLayout.NORTH);
        p.add(s, BorderLayout.CENTER);
        return p;
    }

    static JTextArea previewArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIUtils.getEditorFont(12));
        area.setMargin(new Insets(8, 10, 8, 10));
        return area;
    }

    static JTable table(javax.swing.table.TableModel model, int... widths) {
        JTable t = new JTable(model);
        t.setRowHeight(26);
        t.setFillsViewportHeight(true);
        t.setShowVerticalLines(false);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setReorderingAllowed(false);
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++) {
            TableColumn c = t.getColumnModel().getColumn(i);
            c.setPreferredWidth(widths[i]);
            if (i == 0) {
                c.setMaxWidth(widths[i]);
            }
        }
        return t;
    }

    static JPanel buttonBar(JComponent left, JButton... right) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new EmptyBorder(8, 12, 10, 12));
        if (left != null) bar.add(left, BorderLayout.WEST);
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        for (JButton b : right) r.add(b);
        bar.add(r, BorderLayout.EAST);
        return bar;
    }

    static JButton primary(String text) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "default");
        b.setBackground(UIUtils.ACCENT_COLOR);
        b.setForeground(Color.WHITE);
        return b;
    }

    static void closeOnEscape(JDialog d) {
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
}

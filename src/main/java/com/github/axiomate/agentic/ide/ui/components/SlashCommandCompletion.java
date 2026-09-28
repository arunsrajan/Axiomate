package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry;
import com.github.axiomate.agentic.ide.plugins.SlashCommandRegistry.SlashCommand;
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
import java.util.List;

/**
 * Shows matching slash commands while the user types "/name" at the start of the chat input.
 */
public class SlashCommandCompletion {

    private final JTextArea input;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<SlashCommand> model = new DefaultListModel<>();
    private final JList<SlashCommand> list = new JList<>(model);

    public SlashCommandCompletion(JTextArea input) {
        this.input = input;
        popup.setFocusable(false);
        list.setFocusable(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                accept();
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(420, 200));
        JLabel header = new JLabel(" Slash commands");
        header.setFont(UIUtils.uiFont(Font.BOLD, 10.5f));
        header.setBorder(new EmptyBorder(3, 6, 3, 6));
        JPanel content = new JPanel(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        popup.add(content);

        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { SwingUtilities.invokeLater(SlashCommandCompletion.this::update); }
            public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(SlashCommandCompletion.this::update); }
            public void changedUpdate(DocumentEvent e) { }
        });
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!popup.isVisible() || e.isControlDown() || e.isMetaDown()) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> move(1);
                    case KeyEvent.VK_UP -> move(-1);
                    case KeyEvent.VK_TAB, KeyEvent.VK_ENTER -> accept();
                    case KeyEvent.VK_ESCAPE -> popup.setVisible(false);
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
    }

    public boolean isPopupVisible() {
        return popup.isVisible();
    }

    /** The "/prefix" being typed, or null when the caret is not inside a leading slash token. */
    private String currentPrefix() {
        String text = input.getText();
        int caret = input.getCaretPosition();
        if (!text.startsWith("/") || caret < 1 || caret > text.length()) return null;
        String head = text.substring(1, caret);
        if (head.chars().anyMatch(Character::isWhitespace) || head.contains("/")) return null;
        return head;
    }

    private void update() {
        String prefix = currentPrefix();
        if (prefix == null || !input.isShowing()) {
            popup.setVisible(false);
            return;
        }
        List<SlashCommand> matches = SlashCommandRegistry.getInstance().complete(prefix);
        if (matches.isEmpty()) {
            popup.setVisible(false);
            return;
        }
        model.clear();
        matches.forEach(model::addElement);
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(8, matches.size()));
        try {
            Rectangle r = input.modelToView2D(0).getBounds();
            popup.show(input, r.x, Math.max(0, r.y - popup.getPreferredSize().height - 4));
        } catch (Exception e) {
            popup.show(input, 0, -popup.getPreferredSize().height);
        }
        input.requestFocusInWindow();
    }

    private void move(int delta) {
        int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void accept() {
        SlashCommand c = list.getSelectedValue();
        popup.setVisible(false);
        if (c == null) return;
        String text = input.getText();
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        String rest = text.substring(end).stripLeading();
        input.setText("/" + c.name() + " " + rest);
        input.setCaretPosition(("/" + c.name() + " ").length());
    }

    private static class Renderer extends JPanel implements ListCellRenderer<SlashCommand> {
        private final JLabel name = new JLabel();
        private final JLabel desc = new JLabel();

        Renderer() {
            super(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(3, 8, 3, 8));
            add(name, BorderLayout.WEST);
            add(desc, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SlashCommand> list, SlashCommand c, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            name.setText("/" + c.name());
            name.setFont(UIUtils.getEditorFont(12).deriveFont(Font.BOLD));
            desc.setText(c.description());
            desc.setFont(UIUtils.uiFont(Font.PLAIN, 11.5f));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(isSelected ? list.getSelectionForeground() : UIUtils.accentText(UIUtils.ACCENT_COLOR));
            desc.setForeground(isSelected ? list.getSelectionForeground() : UIUtils.mutedForeground());
            return this;
        }
    }
}

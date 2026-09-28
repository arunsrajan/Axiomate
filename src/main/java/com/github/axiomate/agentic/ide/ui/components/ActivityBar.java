package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * VS Code style vertical activity bar that switches the sidebar view. Clicking the active
 * view again collapses the sidebar.
 */
public class ActivityBar extends JPanel {

    private final JPanel top = new JPanel();
    private final JPanel bottom = new JPanel();
    private final Map<String, JToggleButton> buttons = new LinkedHashMap<>();
    private final ButtonGroup group = new ButtonGroup();
    private final Consumer<String> onSelect;
    private String selectedId;

    public ActivityBar(Consumer<String> onSelect) {
        super(new BorderLayout());
        this.onSelect = onSelect;
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        bottom.setOpaque(false);
        setBorder(new EmptyBorder(6, 0, 6, 0));
        add(top, BorderLayout.NORTH);
        add(bottom, BorderLayout.SOUTH);
        applyColors();
        UIUtils.addThemeListener(this::applyColors);
    }

    private void applyColors() {
        setBackground(UIUtils.surface(2));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, UIUtils.borderColor()),
                new EmptyBorder(6, 0, 6, 0)));
    }

    public void addView(String id, String tooltip, UIUtils.Glyph glyph) {
        JToggleButton b = new JToggleButton(UIUtils.glyph(glyph, 22, null)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSelected()) {
                    g2.setColor(UIUtils.tint(UIUtils.ACCENT_COLOR, 0.18f));
                    g2.fillRoundRect(6, 3, getWidth() - 12, getHeight() - 6, 10, 10);
                    g2.setColor(UIUtils.ACCENT_COLOR);
                    g2.fillRoundRect(0, 8, 3, getHeight() - 16, 3, 3);
                } else if (getModel().isRollover()) {
                    g2.setColor(UIUtils.tint(UIUtils.foreground(), 0.08f));
                    g2.fillRoundRect(6, 3, getWidth() - 12, getHeight() - 6, 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        styleButton(b, tooltip);
        b.addActionListener(e -> {
            if (id.equals(selectedId)) {
                // Clicking the active view toggles the sidebar
                onSelect.accept(null);
            } else {
                select(id);
                onSelect.accept(id);
            }
        });
        buttons.put(id, b);
        group.add(b);
        top.add(b);
        top.add(Box.createVerticalStrut(2));
    }

    public void addAction(String tooltip, UIUtils.Glyph glyph, Runnable action) {
        JButton b = new JButton(UIUtils.glyph(glyph, 20, null));
        styleButton(b, tooltip);
        b.addActionListener(e -> action.run());
        bottom.add(b);
    }

    private void styleButton(AbstractButton b, String tooltip) {
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setRolloverEnabled(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Dimension d = new Dimension(48, 44);
        b.setPreferredSize(d);
        b.setMaximumSize(d);
        b.setMinimumSize(d);
        b.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    /**
     * Marks a view as selected without firing the callback; null clears the selection.
     */
    public void select(String id) {
        selectedId = id;
        if (id == null) {
            group.clearSelection();
        } else {
            JToggleButton b = buttons.get(id);
            if (b != null) b.setSelected(true);
        }
        repaint();
    }

    public String getSelectedId() {
        return selectedId;
    }
}

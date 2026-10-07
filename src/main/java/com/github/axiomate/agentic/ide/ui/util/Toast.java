package com.github.axiomate.agentic.ide.ui.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking notification shown in the bottom-right corner of the owning window.
 * Replaces modal "success" dialogs so feedback does not interrupt the developer.
 */
public final class Toast {

    private static final Logger log = LoggerFactory.getLogger(Toast.class);
    private static final List<JWindow> visible = new ArrayList<>();

    public enum Kind {
        INFO(UIUtils.ACCENT_COLOR), SUCCESS(UIUtils.SUCCESS_COLOR), WARNING(UIUtils.WARNING_COLOR), ERROR(UIUtils.ERROR_COLOR);

        final Color color;

        Kind(Color color) {
            this.color = color;
        }
    }

    private Toast() {
    }

    public static void success(Component anchor, String message) {
        show(anchor, message, Kind.SUCCESS);
    }

    public static void info(Component anchor, String message) {
        show(anchor, message, Kind.INFO);
    }

    public static void warning(Component anchor, String message) {
        show(anchor, message, Kind.WARNING);
    }

    public static void error(Component anchor, String message) {
        show(anchor, message, Kind.ERROR);
    }

    public static void show(Component anchor, String message, Kind kind) {
        log.info("[{}] {}", kind, message);
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> display(anchor, message, kind));
    }

    private static void display(Component anchor, String message, Kind kind) {
        Window owner = anchor instanceof Window w ? w : (anchor != null ? SwingUtilities.getWindowAncestor(anchor) : null);
        if (owner == null || !owner.isShowing()) {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w instanceof Frame) {
                    owner = w;
                    break;
                }
            }
        }
        if (owner == null) return;

        JWindow window = new JWindow(owner);
        window.setBackground(new Color(0, 0, 0, 0));
        JPanel panel = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(UIUtils.surface(2));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
                g2.setColor(UIUtils.borderColor());
                g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
                g2.setColor(kind.color);
                g2.fillRoundRect(6, 8, 4, getHeight() - 16, 4, 4);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(10, 18, 10, 16));
        JLabel label = new JLabel("<html><body style='width: 300px'>" + escape(message).replace("\n", "<br>") + "</body></html>");
        label.setForeground(UIUtils.foreground());
        label.setFont(UIUtils.uiFont(Font.PLAIN, 12.5f));
        panel.add(label, BorderLayout.CENTER);
        window.setContentPane(panel);
        window.pack();

        synchronized (visible) {
            int offset = 0;
            for (JWindow w : visible) offset += w.getHeight() + 8;
            Point origin = owner.getLocationOnScreen();
            window.setLocation(origin.x + owner.getWidth() - window.getWidth() - 24,
                    origin.y + owner.getHeight() - window.getHeight() - 48 - offset);
            visible.add(window);
        }
        window.setVisible(true);

        int millis = kind == Kind.ERROR ? 7000 : 4000;
        Timer timer = new Timer(millis, e -> {
            window.dispose();
            synchronized (visible) {
                visible.remove(window);
            }
        });
        timer.setRepeats(false);
        timer.start();
        panel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                timer.stop();
                window.dispose();
                synchronized (visible) {
                    visible.remove(window);
                }
            }
        });
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package com.github.agentforge.agentic.ide.ui.util;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * UI Utilities, themes, and vector icons for AgentForge IDE.
 */
public class UIUtils {

    private static final Logger log = LoggerFactory.getLogger(UIUtils.class);

    public static final Color ACCENT_COLOR = new Color(59, 130, 246);       // Vibrant Blue
    public static final Color ACCENT_PURPLE = new Color(139, 92, 246);     // AI Purple
    public static final Color SUCCESS_COLOR = new Color(34, 197, 94);       // Emerald Green
    public static final Color WARNING_COLOR = new Color(245, 158, 11);      // Amber
    public static final Color ERROR_COLOR = new Color(239, 68, 68);         // Rose Red

    public static void applyTheme(String themeName, Component rootComponent) {
        try {
            switch (themeName) {
                case "FlatLaf Light" -> FlatLightLaf.setup();
                case "FlatLaf Dark" -> FlatDarkLaf.setup();
                case "IntelliJ Light" -> FlatIntelliJLaf.setup();
                case "FlatLaf Darcula", "Darcula" -> FlatDarculaLaf.setup();
                default -> {
                    try {
                        Class<?> themeClass = Class.forName("com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme");
                        themeClass.getMethod("setup").invoke(null);
                    } catch (Exception e) {
                        FlatDarculaLaf.setup();
                    }
                }
            }
            if (rootComponent != null) {
                SwingUtilities.updateComponentTreeUI(rootComponent);
            }
        } catch (Exception e) {
            log.warn("Could not apply theme: {}", themeName, e);
            FlatDarculaLaf.setup();
        }
    }

    public static Font getEditorFont(int size) {
        String[] fontNames = {"JetBrains Mono", "Cascadia Code", "Fira Code", "Consolas", "Courier New", "Monospaced"};
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] available = ge.getAvailableFontFamilyNames();

        for (String preferred : fontNames) {
            for (String font : available) {
                if (font.equalsIgnoreCase(preferred)) {
                    return new Font(font, Font.PLAIN, size);
                }
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    public static Border createPaddedBorder(int top, int left, int bottom, int right) {
        return new EmptyBorder(top, left, bottom, right);
    }

    public static JButton createPillButton(String text, Icon icon, Color background, Color foreground) {
        JButton btn = new JButton(text, icon) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed()) {
                    g2.setColor(background.darker());
                } else if (getModel().isRollover()) {
                    g2.setColor(background.brighter());
                } else {
                    g2.setColor(background);
                }
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setForeground(foreground);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(6, 12, 6, 12));
        return btn;
    }

    // High-Res DPI-Aware Vector Icons
    public static Icon createSparkleIcon(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : ACCENT_PURPLE);
                int cx = x + size / 2;
                int cy = y + size / 2;
                int r = size / 2 - 2;

                Path2D path = new Path2D.Float();
                path.moveTo(cx, cy - r);
                path.quadTo(cx, cy, cx + r, cy);
                path.quadTo(cx, cy, cx, cy + r);
                path.quadTo(cx, cy, cx - r, cy);
                path.quadTo(cx, cy, cx, cy - r);
                path.closePath();

                g2.fill(path);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }
            @Override
            public int getIconHeight() { return size; }
        };
    }

    public static Icon createPlayIcon(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : SUCCESS_COLOR);
                int[] px = {x + 3, x + size - 3, x + 3};
                int[] py = {y + 2, y + size / 2, y + size - 2};
                g2.fillPolygon(px, py, 3);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }
            @Override
            public int getIconHeight() { return size; }
        };
    }

    public static Icon createStopIcon(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : ERROR_COLOR);
                g2.fillRoundRect(x + 3, y + 3, size - 6, size - 6, 4, 4);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }
            @Override
            public int getIconHeight() { return size; }
        };
    }

    public static Icon createFileIcon(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : Color.LIGHT_GRAY);
                g2.drawRoundRect(x + 3, y + 2, size - 6, size - 4, 3, 3);
                g2.drawLine(x + 5, y + 6, x + size - 6, y + 6);
                g2.drawLine(x + 5, y + 9, x + size - 6, y + 9);
                g2.drawLine(x + 5, y + 12, x + size - 9, y + 12);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }
            @Override
            public int getIconHeight() { return size; }
        };
    }

    public static Icon createFolderIcon(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : new Color(234, 179, 8));
                g2.fillRoundRect(x + 2, y + 4, size - 4, size - 6, 3, 3);
                g2.fillRoundRect(x + 2, y + 2, size / 2, 4, 2, 2);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }
            @Override
            public int getIconHeight() { return size; }
        };
    }

    public static Icon createGearIcon(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color != null ? color : Color.GRAY);
                int cx = x + size / 2;
                int cy = y + size / 2;
                g2.drawOval(cx - 4, cy - 4, 8, 8);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    int x1 = cx + (int) (5 * Math.cos(angle));
                    int y1 = cy + (int) (5 * Math.sin(angle));
                    int x2 = cx + (int) (7 * Math.cos(angle));
                    int y2 = cy + (int) (7 * Math.sin(angle));
                    g2.drawLine(x1, y1, x2, y2);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }
            @Override
            public int getIconHeight() { return size; }
        };
    }
}

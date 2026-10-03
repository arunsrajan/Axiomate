package com.github.axiomate.agentic.ide.ui.util;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.github.axiomate.agentic.ide.ui.theme.ClaudeTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * UI Utilities, themes, and vector icons for Axiomate IDE.
 */
public class UIUtils {

    private static final Logger log = LoggerFactory.getLogger(UIUtils.class);

    // Semantic colors; Claude themes replace them with Claude Code's palette when applied
    public static volatile Color ACCENT_COLOR = new Color(59, 130, 246);       // Primary accent
    public static volatile Color ACCENT_PURPLE = new Color(139, 92, 246);     // Secondary / AI accent
    public static volatile Color SUCCESS_COLOR = new Color(34, 197, 94);
    public static volatile Color WARNING_COLOR = new Color(245, 158, 11);
    public static volatile Color ERROR_COLOR = new Color(239, 68, 68);

    public static final String DEFAULT_THEME = "Claude Dark";
    public static final String[] THEMES = {"Claude Dark", "Claude Light", "Claude Dark (Colorblind)", "Claude Light (Colorblind)",
            "FlatLaf Darcula", "FlatLaf Dark", "FlatLaf Light", "IntelliJ Light", "One Dark"};

    private static volatile ClaudeTheme activeClaudeTheme;

    /** The active Claude theme, or null when a classic FlatLaf theme is in use. */
    public static ClaudeTheme claudeTheme() {
        return activeClaudeTheme;
    }

    private static void setSemanticColors(ClaudeTheme t) {
        if (t != null) {
            ACCENT_COLOR = t.claude();
            ACCENT_PURPLE = t.permission();
            SUCCESS_COLOR = t.success();
            WARNING_COLOR = t.warning();
            ERROR_COLOR = t.error();
        } else {
            ACCENT_COLOR = new Color(59, 130, 246);
            ACCENT_PURPLE = new Color(139, 92, 246);
            SUCCESS_COLOR = new Color(34, 197, 94);
            WARNING_COLOR = new Color(245, 158, 11);
            ERROR_COLOR = new Color(239, 68, 68);
        }
    }

    private static final List<Runnable> themeListeners = new CopyOnWriteArrayList<>();

    /**
     * Registers a callback run after the look-and-feel changes, so components with computed colors can refresh.
     */
    public static void addThemeListener(Runnable listener) {
        themeListeners.add(listener);
    }

    public static void applyTheme(String themeName, Component rootComponent) {
        try {
            ClaudeTheme claude = ClaudeTheme.byName(themeName == null ? "" : themeName).orElse(null);
            activeClaudeTheme = claude;
            setSemanticColors(claude);
            FlatLaf.setGlobalExtraDefaults(claude != null ? claude.lafDefaults() : null);
            if (claude != null) {
                if (claude.isDark()) FlatDarkLaf.setup();
                else FlatLightLaf.setup();
            } else switch (themeName == null ? "" : themeName) {
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
            applyModernDefaults();
            if (rootComponent != null) {
                SwingUtilities.updateComponentTreeUI(rootComponent);
                for (Window w : Window.getWindows()) {
                    if (w != rootComponent && w.isDisplayable()) {
                        SwingUtilities.updateComponentTreeUI(w);
                    }
                }
            }
            for (Runnable r : themeListeners) {
                try {
                    r.run();
                } catch (Exception ex) {
                    log.warn("Theme listener failed", ex);
                }
            }
        } catch (Exception e) {
            log.warn("Could not apply theme: {}", themeName, e);
            FlatDarculaLaf.setup();
        }
    }

    /**
     * Rounded, roomier FlatLaf defaults for a more modern look.
     */
    private static void applyModernDefaults() {
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("CheckBox.arc", 4);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabSelectionHeight", 2);
        UIManager.put("TabbedPane.tabHeight", 30);
        UIManager.put("Tree.rowHeight", 22);
        UIManager.put("List.cellMargins", new Insets(3, 6, 3, 6));
        UIManager.put("PopupMenu.borderCornerRadius", 8);
        UIManager.put("Popup.borderCornerRadius", 8);
    }

    // ------------------------------------------------------------------
    // Theme-aware palette (computed from the active look-and-feel)
    // ------------------------------------------------------------------

    public static boolean isDark() {
        try {
            return FlatLaf.isLafDark();
        } catch (Throwable t) {
            Color bg = UIManager.getColor("Panel.background");
            return bg == null || luminance(bg) < 0.5;
        }
    }

    private static Color ui(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? new Color(c.getRGB(), true) : fallback;
    }

    public static Color panelBackground() {
        return ui("Panel.background", new Color(43, 43, 43));
    }

    public static Color foreground() {
        return ui("Label.foreground", isDark() ? new Color(220, 220, 220) : new Color(30, 30, 30));
    }

    public static Color mutedForeground() {
        return blend(foreground(), panelBackground(), 0.45f);
    }

    public static Color borderColor() {
        return ui("Component.borderColor", blend(foreground(), panelBackground(), 0.8f));
    }

    /** A background one step removed from the panel color (for headers, bars and cards). */
    public static Color surface(int level) {
        Color bg = panelBackground();
        float amount = 0.035f * level;
        return isDark() ? blend(bg, Color.BLACK, amount * 2.2f) : blend(bg, Color.WHITE, Math.min(1f, amount * 6f));
    }

    /** A soft tint of an accent color over the panel background (for chips and bubbles). */
    public static Color tint(Color accent, float strength) {
        return blend(panelBackground(), accent, strength);
    }

    /** An accent color adjusted for legible text on the current background. */
    public static Color accentText(Color accent) {
        return isDark() ? blend(accent, Color.WHITE, 0.35f) : blend(accent, Color.BLACK, 0.25f);
    }

    public static Color consoleBackground() {
        return isDark() ? blend(panelBackground(), Color.BLACK, 0.35f) : new Color(250, 250, 252);
    }

    public static Color consoleForeground() {
        return isDark() ? new Color(220, 220, 220) : new Color(35, 38, 45);
    }

    public static Color blend(Color a, Color b, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * r),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * r),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * r));
    }

    private static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
    }

    public static Font uiFont(int style, float size) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        return base.deriveFont(style, size);
    }

    /** Small uppercase section header, e.g. for sidebar views. */
    public static JLabel sectionHeader(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(uiFont(Font.BOLD, 11f));
        l.setForeground(mutedForeground());
        return l;
    }

    /** Borderless icon button used in headers and toolbars. */
    public static JButton iconButton(Icon icon, String tooltip, java.awt.event.ActionListener action) {
        JButton b = new JButton(icon);
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (action != null) b.addActionListener(action);
        return b;
    }

    /** Borderless text button used in headers and toolbars. */
    public static JButton flatButton(String text, String tooltip, java.awt.event.ActionListener action) {
        JButton b = new JButton(text);
        b.setToolTipText(tooltip);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (action != null) b.addActionListener(action);
        return b;
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

    // ------------------------------------------------------------------
    // Activity bar & action icons (stroke-drawn, DPI independent)
    // ------------------------------------------------------------------

    public enum Glyph {EXPLORER, SESSIONS, MEMORY, SYNC, PLUGINS, SEARCH, PLUS, TRASH, PIN, DOWNLOAD, UPLOAD, MORE, REFRESH, TERMINAL, COMMAND, DOT, ELBOW, SPARK}

    /**
     * Stroke-based glyph icon; a null color follows the current theme's foreground.
     */
    public static Icon glyph(Glyph glyph, int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color col = color != null ? color : (c != null && !c.isEnabled() ? mutedForeground() : foreground());
                g2.setColor(col);
                float s = size;
                g2.translate(x, y);
                g2.setStroke(new BasicStroke(Math.max(1.3f, s / 13f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                switch (glyph) {
                    case EXPLORER -> {
                        Path2D p = new Path2D.Float();
                        p.moveTo(s * .22, s * .12);
                        p.lineTo(s * .58, s * .12);
                        p.lineTo(s * .78, s * .32);
                        p.lineTo(s * .78, s * .88);
                        p.lineTo(s * .22, s * .88);
                        p.closePath();
                        g2.draw(p);
                        g2.draw(new Line2D.Float(s * .58f, s * .12f, s * .58f, s * .32f));
                        g2.draw(new Line2D.Float(s * .58f, s * .32f, s * .78f, s * .32f));
                    }
                    case SESSIONS -> {
                        g2.draw(new RoundRectangle2D.Float(s * .1f, s * .16f, s * .56f, s * .42f, s * .16f, s * .16f));
                        Path2D p = new Path2D.Float();
                        p.moveTo(s * .36, s * .7);
                        p.lineTo(s * .36, s * .78);
                        p.lineTo(s * .78, s * .78);
                        p.lineTo(s * .9, s * .9);
                        p.lineTo(s * .9, s * .42);
                        p.lineTo(s * .76, s * .42);
                        g2.draw(p);
                    }
                    case MEMORY -> {
                        g2.draw(new RoundRectangle2D.Float(s * .22f, s * .22f, s * .56f, s * .56f, s * .12f, s * .12f));
                        g2.draw(new RoundRectangle2D.Float(s * .38f, s * .38f, s * .24f, s * .24f, s * .06f, s * .06f));
                        for (float k : new float[]{.36f, .5f, .64f}) {
                            g2.draw(new Line2D.Float(s * k, s * .08f, s * k, s * .22f));
                            g2.draw(new Line2D.Float(s * k, s * .78f, s * k, s * .92f));
                            g2.draw(new Line2D.Float(s * .08f, s * k, s * .22f, s * k));
                            g2.draw(new Line2D.Float(s * .78f, s * k, s * .92f, s * k));
                        }
                    }
                    case SYNC, REFRESH -> {
                        g2.draw(new java.awt.geom.Arc2D.Float(s * .16f, s * .16f, s * .68f, s * .68f, 30, 150, java.awt.geom.Arc2D.OPEN));
                        g2.draw(new java.awt.geom.Arc2D.Float(s * .16f, s * .16f, s * .68f, s * .68f, 210, 150, java.awt.geom.Arc2D.OPEN));
                        g2.draw(new Line2D.Float(s * .79f, s * .33f, s * .8f, s * .14f));
                        g2.draw(new Line2D.Float(s * .79f, s * .33f, s * .6f, s * .3f));
                        g2.draw(new Line2D.Float(s * .21f, s * .67f, s * .2f, s * .86f));
                        g2.draw(new Line2D.Float(s * .21f, s * .67f, s * .4f, s * .7f));
                    }
                    case PLUGINS -> {
                        Path2D p = new Path2D.Float();
                        p.moveTo(s * .16, s * .3);
                        p.lineTo(s * .4, s * .3);
                        p.quadTo(s * .4, s * .12, s * .52, s * .12);
                        p.quadTo(s * .64, s * .12, s * .64, s * .3);
                        p.lineTo(s * .84, s * .3);
                        p.lineTo(s * .84, s * .5);
                        p.quadTo(s * .98, s * .5, s * .98, s * .62);
                        p.quadTo(s * .98, s * .74, s * .84, s * .74);
                        p.lineTo(s * .84, s * .88);
                        p.lineTo(s * .16, s * .88);
                        p.closePath();
                        g2.draw(p);
                    }
                    case SEARCH -> {
                        g2.draw(new Ellipse2D.Float(s * .14f, s * .14f, s * .5f, s * .5f));
                        g2.draw(new Line2D.Float(s * .56f, s * .56f, s * .86f, s * .86f));
                    }
                    case PLUS -> {
                        g2.draw(new Line2D.Float(s * .5f, s * .2f, s * .5f, s * .8f));
                        g2.draw(new Line2D.Float(s * .2f, s * .5f, s * .8f, s * .5f));
                    }
                    case TRASH -> {
                        g2.draw(new Line2D.Float(s * .18f, s * .26f, s * .82f, s * .26f));
                        g2.draw(new Line2D.Float(s * .4f, s * .14f, s * .6f, s * .14f));
                        Path2D p = new Path2D.Float();
                        p.moveTo(s * .26, s * .26);
                        p.lineTo(s * .32, s * .88);
                        p.lineTo(s * .68, s * .88);
                        p.lineTo(s * .74, s * .26);
                        g2.draw(p);
                    }
                    case PIN -> {
                        g2.draw(new Ellipse2D.Float(s * .3f, s * .12f, s * .4f, s * .4f));
                        g2.draw(new Line2D.Float(s * .5f, s * .52f, s * .5f, s * .9f));
                    }
                    case DOWNLOAD, UPLOAD -> {
                        boolean down = glyph == Glyph.DOWNLOAD;
                        g2.draw(new Line2D.Float(s * .5f, s * .12f, s * .5f, s * .66f));
                        float tip = down ? .66f : .12f;
                        float wing = down ? .46f : .32f;
                        g2.draw(new Line2D.Float(s * .5f, s * tip, s * .3f, s * wing));
                        g2.draw(new Line2D.Float(s * .5f, s * tip, s * .7f, s * wing));
                        g2.draw(new Line2D.Float(s * .16f, s * .86f, s * .84f, s * .86f));
                    }
                    case MORE -> {
                        for (float k : new float[]{.22f, .5f, .78f}) {
                            g2.fill(new Ellipse2D.Float(s * k - s * .07f, s * .43f, s * .14f, s * .14f));
                        }
                    }
                    case TERMINAL -> {
                        g2.draw(new RoundRectangle2D.Float(s * .1f, s * .18f, s * .8f, s * .64f, s * .12f, s * .12f));
                        g2.draw(new Line2D.Float(s * .26f, s * .38f, s * .4f, s * .5f));
                        g2.draw(new Line2D.Float(s * .4f, s * .5f, s * .26f, s * .62f));
                        g2.draw(new Line2D.Float(s * .48f, s * .64f, s * .7f, s * .64f));
                    }
                    case DOT -> g2.fill(new Ellipse2D.Float(s * .3f, s * .3f, s * .4f, s * .4f));
                    case ELBOW -> {
                        // Claude Code's "⎿" connector in front of tool results
                        g2.draw(new Line2D.Float(s * .35f, s * .05f, s * .35f, s * .6f));
                        g2.draw(new Line2D.Float(s * .35f, s * .6f, s * .9f, s * .6f));
                    }
                    case SPARK -> {
                        // Claude's "✻" asterisk: six rounded spokes
                        g2.setStroke(new BasicStroke(Math.max(1.6f, s / 7.5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        for (int i = 0; i < 6; i++) {
                            double a = Math.PI / 6 + i * Math.PI / 3;
                            float x2 = (float) (s / 2 + Math.cos(a) * s * .42);
                            float y2 = (float) (s / 2 + Math.sin(a) * s * .42);
                            g2.draw(new Line2D.Float(s / 2f, s / 2f, x2, y2));
                        }
                    }
                    case COMMAND -> {
                        g2.draw(new RoundRectangle2D.Float(s * .12f, s * .12f, s * .76f, s * .76f, s * .2f, s * .2f));
                        g2.draw(new Line2D.Float(s * .56f, s * .3f, s * .42f, s * .7f));
                    }
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }
}

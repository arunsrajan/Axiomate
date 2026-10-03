package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Claude Code style activity line shown above the prompt while the agent works:
 * a rotating ✻ spark, the current activity, elapsed time and the interrupt hint.
 */
public class ThinkingIndicator extends JPanel {

    private final JLabel spark = new JLabel();
    private final JLabel text = new JLabel();
    private final Timer timer;
    private String activity = "Thinking";
    private long startedAt;
    private int frame;

    public ThinkingIndicator() {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);
        setBorder(new EmptyBorder(2, 4, 4, 4));
        Font f = UIUtils.getEditorFont(12);
        text.setFont(f);
        add(spark);
        add(text);
        timer = new Timer(120, e -> tick());
        setVisible(false);
    }

    public void start(String activityText) {
        activity = activityText == null || activityText.isBlank() ? "Thinking" : activityText;
        if (!timer.isRunning()) {
            startedAt = System.currentTimeMillis();
            timer.start();
        }
        setVisible(true);
        tick();
    }

    public void stop() {
        timer.stop();
        setVisible(false);
    }

    public boolean isRunning() {
        return timer.isRunning();
    }

    String currentText() {
        return text.getText();
    }

    private void tick() {
        frame++;
        double angle = frame * Math.PI / 12;
        Color accent = UIUtils.ACCENT_COLOR;
        Icon base = UIUtils.glyph(UIUtils.Glyph.SPARK, 14, accent);
        spark.setIcon(new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.rotate(angle, x + 7, y + 7);
                base.paintIcon(c, g2, x, y);
                g2.dispose();
            }

            public int getIconWidth() {
                return 14;
            }

            public int getIconHeight() {
                return 14;
            }
        });
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        String label = activity.endsWith("…") || activity.endsWith("...") ? activity : activity + "…";
        text.setText(label + "  (" + secs + "s · esc to interrupt)");
        text.setForeground(accent);
    }
}

package com.github.axiomate.agentic.ide.ui.util;

import javax.swing.*;
import java.awt.*;

/**
 * A panel that always matches its scroll pane's viewport width, so content wraps or truncates instead of
 * producing a horizontal scrollbar (used for sidebar lists, cards and dialog bodies).
 */
public class ScrollablePanel extends JPanel implements Scrollable {

    public ScrollablePanel(LayoutManager layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /**
     * A vertical scroll pane without a horizontal scrollbar around the given view.
     */
    public static JScrollPane verticalScroll(Component view) {
        JScrollPane scroll = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /**
     * A JList that fits the viewport width so long cells are truncated with an ellipsis.
     */
    public static <T> JList<T> widthTrackingList(ListModel<T> model) {
        return new JList<>(model) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };
    }
}

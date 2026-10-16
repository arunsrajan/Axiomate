package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * A modern styled table component rendering Markdown tables within AI agent response messages.
 * Includes column alignments, zebra striping, inline markdown styling, and clipboard export options.
 */
public class MarkdownTablePanel extends JPanel {

    private final MarkdownTableParser.TableSegment segment;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private final JScrollPane scrollPane;
    private final JLabel titleLabel;
    private final JButton copyBtn;
    private final JTextArea rawArea;

    public MarkdownTablePanel(MarkdownTableParser.TableSegment segment) {
        super(new BorderLayout(0, 0));
        this.segment = segment;
        setOpaque(false);

        // Hidden raw markdown text for accessibility and test inspection
        rawArea = new JTextArea(segment.rawMarkdown());
        rawArea.setVisible(false);
        add(rawArea, BorderLayout.SOUTH);

        // 1. Build Table Model
        String[] columnNames = segment.headers().toArray(new String[0]);
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        for (List<String> row : segment.rows()) {
            tableModel.addRow(row.toArray(new Object[0]));
        }

        // 2. Initialize JTable
        table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(true);
        table.setRowHeight(26);
        table.setIntercellSpacing(new Dimension(1, 1));
        table.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        table.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        // 3. Header styling
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 28));
        header.setFont(UIUtils.uiFont(Font.BOLD, 12f));

        // 4. Configure Column Renderers & Alignments
        List<Integer> alignments = segment.alignments();
        for (int i = 0; i < table.getColumnCount(); i++) {
            int align = i < alignments.size() ? alignments.get(i) : SwingConstants.LEFT;
            TableColumn col = table.getColumnModel().getColumn(i);
            col.setCellRenderer(new ModernTableCellRenderer(align));
            col.setHeaderRenderer(new ModernHeaderRenderer(align));
        }

        // 5. Calculate Column Widths
        autoFitColumns();

        // 6. Scroll Pane & Sizing
        scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);

        int visibleRowCount = Math.min(Math.max(segment.rows().size(), 1), 14);
        int targetHeight = 28 /* header */ + (visibleRowCount * 26) + 4;
        scrollPane.setPreferredSize(new Dimension(scrollPane.getPreferredSize().width, targetHeight));
        scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 380));

        // 7. Top Bar with Table Summary and Copy Action
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setOpaque(false);
        topBar.setBorder(new EmptyBorder(4, 8, 4, 6));

        titleLabel = new JLabel("Table • " + segment.rows().size() + " " + (segment.rows().size() == 1 ? "row" : "rows")
                + ", " + segment.headers().size() + " cols");
        titleLabel.setFont(UIUtils.uiFont(Font.PLAIN, 11f));

        copyBtn = new JButton("Copy");
        copyBtn.setFont(UIUtils.uiFont(Font.PLAIN, 11f));
        copyBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        copyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyBtn.setToolTipText("Copy table as Markdown or TSV");
        copyBtn.addActionListener(e -> copyTableToClipboard(segment.toMarkdown()));

        // Right click on Copy button or table shows format choices
        JPopupMenu copyMenu = new JPopupMenu();
        JMenuItem copyMdItem = new JMenuItem("Copy as Markdown");
        copyMdItem.addActionListener(e -> copyTableToClipboard(segment.toMarkdown()));
        JMenuItem copyTsvItem = new JMenuItem("Copy as TSV (Excel/Sheets)");
        copyTsvItem.addActionListener(e -> copyTableToClipboard(segment.toTsv()));
        copyMenu.add(copyMdItem);
        copyMenu.add(copyTsvItem);

        copyBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                    copyMenu.show(copyBtn, 0, copyBtn.getHeight());
                }
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                    copyMenu.show(table, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    copyMenu.show(table, e.getX(), e.getY());
                }
            }
        });

        topBar.add(titleLabel, BorderLayout.WEST);
        topBar.add(copyBtn, BorderLayout.EAST);

        // Main Container Card
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(true);
        card.add(topBar, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);

        // Apply theme styling initially and on theme change
        Runnable themeApplier = () -> applyThemeStyles(card, topBar);
        themeApplier.run();
        UIUtils.addThemeListener(() -> SwingUtilities.invokeLater(themeApplier));

        add(card, BorderLayout.CENTER);
        setBorder(new EmptyBorder(4, 0, 4, 0));
    }

    private void autoFitColumns() {
        FontMetrics fm = table.getFontMetrics(UIUtils.uiFont(Font.PLAIN, 12f));
        FontMetrics hfm = table.getFontMetrics(UIUtils.uiFont(Font.BOLD, 12f));
        int totalWidth = 0;

        for (int col = 0; col < table.getColumnCount(); col++) {
            TableColumn column = table.getColumnModel().getColumn(col);
            String hName = table.getColumnName(col);
            int width = hfm.stringWidth(hName) + 24;

            for (int row = 0; row < table.getRowCount(); row++) {
                Object val = table.getValueAt(row, col);
                if (val != null) {
                    String clean = val.toString().replaceAll("[`*_~]", "");
                    width = Math.max(width, fm.stringWidth(clean) + 24);
                }
            }

            width = Math.max(width, 70);
            width = Math.min(width, 380);
            column.setPreferredWidth(width);
            totalWidth += width;
        }

        if (totalWidth <= 760) {
            table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        } else {
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        }
    }

    private void applyThemeStyles(JPanel card, JPanel topBar) {
        Color border = UIUtils.borderColor();
        Color bg = UIUtils.surface(1);
        Color fg = UIUtils.foreground();
        Color muted = UIUtils.mutedForeground();

        card.setBackground(bg);
        card.setBorder(new CompoundBorder(
                new MatteBorder(1, 1, 1, 1, border),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
        ));

        topBar.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, border),
                new EmptyBorder(4, 10, 4, 8)
        ));
        titleLabel.setForeground(muted);
        copyBtn.setForeground(muted);

        table.setBackground(bg);
        table.setForeground(fg);
        table.setGridColor(border);
        Color selBg = UIManager.getColor("Table.selectionBackground");
        if (selBg == null) selBg = UIUtils.tint(UIUtils.ACCENT_COLOR, 0.25f);
        table.setSelectionBackground(selBg);

        revalidate();
        repaint();
    }

    private void copyTableToClipboard(String content) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
            copyBtn.setText("✓ Copied");
            copyBtn.setForeground(UIUtils.SUCCESS_COLOR);
            Timer timer = new Timer(1500, e -> {
                copyBtn.setText("Copy");
                copyBtn.setForeground(UIUtils.mutedForeground());
            });
            timer.setRepeats(false);
            timer.start();
        } catch (Exception ignored) {
        }
    }

    public JTable getTable() {
        return table;
    }

    public MarkdownTableParser.TableSegment getSegment() {
        return segment;
    }

    public String getRawMarkdown() {
        return segment.rawMarkdown();
    }

    // --- Custom Renderers ---

    private static class ModernHeaderRenderer extends DefaultTableCellRenderer {
        private final int alignment;

        ModernHeaderRenderer(int alignment) {
            this.alignment = alignment;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setHorizontalAlignment(alignment);
            label.setFont(UIUtils.uiFont(Font.BOLD, 12f));
            label.setForeground(UIUtils.foreground());
            label.setBackground(UIUtils.surface(2));
            label.setBorder(new CompoundBorder(
                    new MatteBorder(0, 0, 1, 0, UIUtils.borderColor()),
                    new EmptyBorder(4, 10, 4, 10)
            ));
            return label;
        }
    }

    private static class ModernTableCellRenderer extends DefaultTableCellRenderer {
        private final int alignment;

        ModernTableCellRenderer(int alignment) {
            this.alignment = alignment;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setHorizontalAlignment(alignment);
            label.setFont(UIUtils.uiFont(Font.PLAIN, 12f));
            label.setBorder(new EmptyBorder(3, 10, 3, 10));

            String text = value != null ? value.toString() : "";
            if (hasInlineMarkdown(text)) {
                label.setText("<html>" + formatInlineMarkdown(text) + "</html>");
            } else {
                label.setText(text);
            }

            if (isSelected) {
                Color sel = table.getSelectionBackground();
                if (sel == null) sel = UIUtils.tint(UIUtils.ACCENT_COLOR, 0.25f);
                label.setBackground(sel);
                label.setForeground(UIUtils.foreground());
            } else {
                Color rowBg = (row % 2 == 0) ? UIUtils.surface(1) : UIUtils.panelBackground();
                label.setBackground(rowBg);
                label.setForeground(UIUtils.foreground());
            }
            return label;
        }

        private boolean hasInlineMarkdown(String str) {
            return str != null && (str.contains("`") || str.contains("**") || str.contains("*") || str.contains("~~"));
        }

        private String formatInlineMarkdown(String str) {
            if (str == null) return "";
            String res = str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            boolean dark = UIUtils.isDark();
            String codeBg = dark ? "#3a3a3a" : "#eaeaea";
            String codeFg = dark ? "#f0f0f0" : "#222222";
            res = res.replaceAll("`([^`]+)`", "<code style='font-family:monospace; background:" + codeBg + "; color:" + codeFg + "; padding:1px 3px;'>$1</code>");
            res = res.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            res = res.replaceAll("\\*([^*]+)\\*", "<i>$1</i>");
            res = res.replaceAll("~~([^~]+)~~", "<strike>$1</strike>");
            return res;
        }
    }
}

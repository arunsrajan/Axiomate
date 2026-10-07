package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.agent.vision.ImageAttachment;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Row of image thumbnails. Used above the prompt for pending attachments (removable) and inside user messages
 * in the transcript (read-only, click to open full size).
 */
public class ImageAttachmentStrip extends JPanel {

    public static final int THUMB = 64;

    private final List<ImageAttachment> images = new ArrayList<>();
    private final boolean removable;
    private final Runnable onChange;

    public ImageAttachmentStrip(boolean removable, Runnable onChange) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        this.removable = removable;
        this.onChange = onChange != null ? onChange : () -> { };
        setOpaque(false);
        setVisible(false);
    }

    public List<ImageAttachment> getImages() {
        return List.copyOf(images);
    }

    public boolean isEmpty() {
        return images.isEmpty();
    }

    public void add(ImageAttachment image) {
        images.add(image);
        rebuild();
    }

    public void clear() {
        images.clear();
        rebuild();
    }

    private void rebuild() {
        removeAll();
        for (ImageAttachment img : images) {
            add(tile(img));
        }
        setVisible(!images.isEmpty());
        revalidate();
        repaint();
        onChange.run();
    }

    private JComponent tile(ImageAttachment img) {
        JLabel thumb = new JLabel(thumbnail(img, THUMB));
        thumb.setToolTipText("<html>" + escape(img.name())
                + (img.width() > 0 ? "<br>" + img.width() + " × " + img.height() + " px" : "")
                + "<br>" + Math.max(1, img.byteSize() / 1024) + " KB · click to view</html>");
        thumb.setBorder(BorderFactory.createLineBorder(UIUtils.borderColor()));
        thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        thumb.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showFullSize(ImageAttachmentStrip.this, img);
            }
        });
        if (!removable) return thumb;

        JLayeredPane layered = new JLayeredPane();
        Dimension d = thumb.getPreferredSize();
        layered.setPreferredSize(new Dimension(d.width + 6, d.height + 6));
        thumb.setBounds(0, 6, d.width, d.height);
        JButton remove = new JButton("×");
        remove.setToolTipText("Remove " + img.name());
        remove.setMargin(new Insets(0, 0, 0, 0));
        remove.setFont(UIUtils.uiFont(Font.BOLD, 11f));
        remove.setFocusable(false);
        remove.putClientProperty("JButton.buttonType", "roundRect");
        remove.setBounds(d.width - 12, 0, 18, 18);
        remove.addActionListener(e -> {
            images.remove(img);
            rebuild();
        });
        layered.add(thumb, JLayeredPane.DEFAULT_LAYER);
        layered.add(remove, JLayeredPane.PALETTE_LAYER);
        return layered;
    }

    /** Square-fit thumbnail; falls back to a generic icon for formats ImageIO cannot decode (e.g. WebP). */
    public static Icon thumbnail(ImageAttachment img, int size) {
        BufferedImage decoded = decode(img);
        if (decoded == null) return UIUtils.createFileIcon(size / 2, UIUtils.ACCENT_COLOR);
        double s = Math.min((double) size / decoded.getWidth(), (double) size / decoded.getHeight());
        s = Math.min(s, 1.0);
        int w = Math.max(1, (int) (decoded.getWidth() * s)), h = Math.max(1, (int) (decoded.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(decoded, 0, 0, w, h, null);
        g.dispose();
        return new ImageIcon(out);
    }

    static BufferedImage decode(ImageAttachment img) {
        try {
            if (img.base64Data() != null) {
                return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(img.base64Data())));
            }
            if (img.path() != null) return ImageIO.read(new File(img.path()));
        } catch (Exception ignored) {
            // shown as a generic icon
        }
        return null;
    }

    static void showFullSize(Component parent, ImageAttachment img) {
        BufferedImage decoded = decode(img);
        if (decoded == null) return;
        JLabel label = new JLabel(new ImageIcon(decoded));
        label.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(label);
        scroll.setPreferredSize(new Dimension(Math.min(decoded.getWidth() + 30, 1000), Math.min(decoded.getHeight() + 30, 750)));
        JOptionPane.showMessageDialog(parent, scroll, img.name(), JOptionPane.PLAIN_MESSAGE);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}

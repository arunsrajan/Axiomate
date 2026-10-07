package com.github.axiomate.agentic.ide.ui.components;

import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VisionUiTest {

    private File previousProject;

    @BeforeEach
    void remember() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    @AfterEach
    void restore() {
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    private static Transferable transferable(DataFlavor flavor, Object data) {
        return new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{flavor}; }
            public boolean isDataFlavorSupported(DataFlavor f) { return f.equals(flavor); }
            public Object getTransferData(DataFlavor f) { return data; }
        };
    }

    @Test
    @DisplayName("Pasted screenshots and dropped image files become prompt attachments; text and other files do not")
    void pasteAndDropImages(@TempDir Path dir) throws Exception {
        AIAgentPanel panel = new AIAgentPanel(() -> "", new TerminalPanel());

        assertTrue(panel.importImages(transferable(DataFlavor.imageFlavor, new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB))));
        assertEquals(1, panel.pendingImages().size());
        assertTrue(panel.pendingImages().get(0).name().startsWith("pasted-"));

        File png = dir.resolve("diagram.png").toFile();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png);
        File txt = Files.writeString(dir.resolve("notes.txt"), "x").toFile();
        assertTrue(panel.importImages(transferable(DataFlavor.javaFileListFlavor, List.of(png, txt))));
        assertEquals(2, panel.pendingImages().size(), "only the image file is attached");
        assertEquals("diagram.png", panel.pendingImages().get(1).name());

        assertFalse(panel.importImages(transferable(DataFlavor.javaFileListFlavor, List.of(txt))));
        assertFalse(panel.importImages(new StringSelection("plain text")), "text keeps the normal paste");
        assertEquals(2, panel.pendingImages().size());
    }

    @Test
    @DisplayName("Opening an image shows a preview tab whose button attaches it to the prompt")
    void imagePreviewTab(@TempDir Path dir) throws Exception {
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        File png = dir.resolve("logo.png").toFile();
        ImageIO.write(new BufferedImage(12, 6, BufferedImage.TYPE_INT_RGB), "png", png);
        EditorPanel editor = new EditorPanel();
        AtomicReference<File> attached = new AtomicReference<>();
        editor.setImageAttachHandler(attached::set);

        editor.openFile(png);
        assertEquals(png, editor.getActiveFile());
        assertNull(editor.getActiveEditor(), "images are not opened as text");
        assertEquals("", editor.getActiveText());
        assertFalse(editor.saveActiveFileAs(), "previews are read-only");

        JButton attach = find(editor, JButton.class, "Attach to agent prompt");
        assertNotNull(attach);
        attach.doClick();
        assertEquals(png, attached.get());
    }

    private static <T extends JComponent> T find(java.awt.Container c, Class<T> type, String text) {
        for (java.awt.Component k : c.getComponents()) {
            if (type.isInstance(k) && k instanceof AbstractButton b && text.equals(b.getText())) return type.cast(k);
            if (k instanceof java.awt.Container cc) {
                T t = find(cc, type, text);
                if (t != null) return t;
            }
        }
        return null;
    }
}

package com.github.agentforge.agentic.ide.ui.components;

import com.github.agentforge.agentic.ide.ui.util.UIUtils;
import com.github.agentforge.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * Project file explorer tree panel with context menus and file management.
 */
public class ProjectTreePanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(ProjectTreePanel.class);

    private final JTree tree;
    private DefaultTreeModel treeModel;
    private final Consumer<File> fileOpenConsumer;
    private final Consumer<File> aiFileAskConsumer;

    public ProjectTreePanel(Consumer<File> fileOpenConsumer, Consumer<File> aiFileAskConsumer) {
        this.fileOpenConsumer = fileOpenConsumer;
        this.aiFileAskConsumer = aiFileAskConsumer;
        setLayout(new BorderLayout());

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(8, 10, 8, 10));

        JLabel titleLabel = new JLabel("PROJECT EXPLORER");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        titleLabel.setForeground(Color.GRAY);

        JButton refreshBtn = new JButton("↻");
        refreshBtn.setToolTipText("Refresh Tree");
        refreshBtn.setContentAreaFilled(false);
        refreshBtn.setBorderPainted(false);
        refreshBtn.setFocusPainted(false);
        refreshBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshBtn.addActionListener(e -> refreshTree());

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(refreshBtn, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        tree = new JTree();
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new FileTreeCellRenderer());
        tree.setBorder(new EmptyBorder(4, 4, 4, 4));

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.getUserObject() instanceof FileNode fn && !fn.file.isDirectory()) {
                            fileOpenConsumer.accept(fn.file);
                        }
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);

        refreshTree();

        ProjectManager.getInstance().addProjectChangeListener(dir -> refreshTree());
    }

    public void refreshTree() {
        File rootDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (rootDir == null || !rootDir.exists()) {
            return;
        }

        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(new FileNode(rootDir));
        populateTree(rootDir, rootNode);
        treeModel = new DefaultTreeModel(rootNode);
        tree.setModel(treeModel);

        tree.expandRow(0);
    }

    private void populateTree(File dir, DefaultMutableTreeNode parentNode) {
        File[] files = dir.listFiles((f, name) -> !name.startsWith(".") && !name.equals("target") && !name.equals(".git"));
        if (files == null) return;

        Arrays.sort(files, Comparator.comparing(File::isFile).thenComparing(File::getName));

        for (File file : files) {
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(new FileNode(file));
            parentNode.add(childNode);
            if (file.isDirectory()) {
                populateTree(file, childNode);
            }
        }
    }

    private void showContextMenu(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;

        tree.setSelectionPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (!(node.getUserObject() instanceof FileNode fn)) return;

        File targetFile = fn.file;
        File parentDir = targetFile.isDirectory() ? targetFile : targetFile.getParentFile();

        JPopupMenu popup = new JPopupMenu();

        if (!targetFile.isDirectory()) {
            JMenuItem openItem = new JMenuItem("Open in Editor", UIUtils.createFileIcon(14, UIUtils.ACCENT_COLOR));
            openItem.addActionListener(ev -> fileOpenConsumer.accept(targetFile));
            popup.add(openItem);

            JMenuItem askAiItem = new JMenuItem("Ask AI Agent About File", UIUtils.createSparkleIcon(14, UIUtils.ACCENT_PURPLE));
            askAiItem.addActionListener(ev -> aiFileAskConsumer.accept(targetFile));
            popup.add(askAiItem);

            popup.addSeparator();
        }

        JMenuItem newFileItem = new JMenuItem("New File...", UIUtils.createFileIcon(14, null));
        newFileItem.addActionListener(ev -> {
            String name = JOptionPane.showInputDialog(this, "Enter file name:", "New File", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.isBlank()) {
                File newF = new File(parentDir, name.trim());
                try {
                    if (newF.createNewFile()) {
                        refreshTree();
                        fileOpenConsumer.accept(newF);
                    }
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Could not create file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        popup.add(newFileItem);

        JMenuItem newFolderItem = new JMenuItem("New Folder...", UIUtils.createFolderIcon(14, null));
        newFolderItem.addActionListener(ev -> {
            String name = JOptionPane.showInputDialog(this, "Enter folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.isBlank()) {
                File newD = new File(parentDir, name.trim());
                if (newD.mkdirs()) {
                    refreshTree();
                }
            }
        });
        popup.add(newFolderItem);

        popup.addSeparator();

        JMenuItem deleteItem = new JMenuItem("Delete", UIUtils.createStopIcon(14, UIUtils.ERROR_COLOR));
        deleteItem.addActionListener(ev -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to delete " + targetFile.getName() + "?", "Delete File",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                deleteRecursively(targetFile);
                refreshTree();
            }
        });
        popup.add(deleteItem);

        popup.show(tree, e.getX(), e.getY());
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public static class FileNode {
        public final File file;

        public FileNode(File file) {
            this.file = file;
        }

        @Override
        public String toString() {
            return file.getName().isEmpty() ? file.getAbsolutePath() : file.getName();
        }
    }

    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        private final Icon folderIcon = UIUtils.createFolderIcon(14, null);
        private final Icon fileIcon = UIUtils.createFileIcon(14, null);

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof FileNode fn) {
                if (fn.file.isDirectory()) {
                    setIcon(folderIcon);
                } else {
                    setIcon(fileIcon);
                }
            }
            return this;
        }
    }
}

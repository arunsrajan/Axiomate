package com.github.agentforge.agentic.ide.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages the workspace directory and active project files for AgentForge IDE.
 */
public class ProjectManager {

    private static final Logger log = LoggerFactory.getLogger(ProjectManager.class);
    private static ProjectManager instance;

    private File currentProjectDirectory;
    private File activeFile;

    public interface FileContentListener {
        void onFileModified(File file, String newContent);
    }

    private final List<Consumer<File>> projectChangeListeners = new ArrayList<>();
    private final List<Consumer<File>> activeFileChangeListeners = new ArrayList<>();
    private final List<FileContentListener> fileContentListeners = new ArrayList<>();

    private ProjectManager() {
        String workingDir = System.getProperty("user.dir");
        currentProjectDirectory = new File(workingDir);
    }

    public static synchronized ProjectManager getInstance() {
        if (instance == null) {
            instance = new ProjectManager();
        }
        return instance;
    }

    public File getCurrentProjectDirectory() {
        return currentProjectDirectory;
    }

    public void setCurrentProjectDirectory(File directory) {
        if (directory != null && directory.exists() && directory.isDirectory()) {
            this.currentProjectDirectory = directory;
            log.info("Project directory changed to: {}", directory.getAbsolutePath());
            for (Consumer<File> listener : projectChangeListeners) {
                try {
                    listener.accept(directory);
                } catch (Exception e) {
                    log.error("Error notifying project change listener", e);
                }
            }
        }
    }

    public File getActiveFile() {
        return activeFile;
    }

    public void setActiveFile(File file) {
        this.activeFile = file;
        for (Consumer<File> listener : activeFileChangeListeners) {
            try {
                listener.accept(file);
            } catch (Exception e) {
                log.error("Error notifying active file listener", e);
            }
        }
    }

    public void addProjectChangeListener(Consumer<File> listener) {
        projectChangeListeners.add(listener);
    }

    public void addActiveFileChangeListener(Consumer<File> listener) {
        activeFileChangeListeners.add(listener);
    }

    public void addFileContentListener(FileContentListener listener) {
        fileContentListeners.add(listener);
    }

    public void notifyFileModified(File file, String newContent) {
        for (FileContentListener listener : fileContentListeners) {
            try {
                listener.onFileModified(file, newContent);
            } catch (Exception e) {
                log.error("Error notifying file content listener", e);
            }
        }
    }

    public String readFile(File file) throws IOException {
        if (file == null || !file.exists()) {
            return "";
        }
        return Files.readString(file.toPath());
    }

    public void writeFile(File file, String content) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        Files.writeString(file.toPath(), content);
    }
}

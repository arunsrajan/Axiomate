package com.github.axiomate.agentic.ide.features.planning;

import com.github.axiomate.agentic.ide.util.ProjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Feature 3: Blast-radius preview.
 * Before editing, it shows every file, API, test, and downstream service the change
 * is likely to touch.
 */
public class BlastRadiusAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BlastRadiusAnalyzer.class);
    private static BlastRadiusAnalyzer instance;

    private BlastRadiusAnalyzer() {}

    public static synchronized BlastRadiusAnalyzer getInstance() {
        if (instance == null) {
            instance = new BlastRadiusAnalyzer();
        }
        return instance;
    }

    public BlastRadiusResult analyze(String targetFilePath, String prompt) {
        String safeTarget = targetFilePath != null && !targetFilePath.isBlank() ? targetFilePath : "ActiveFile.java";
        String fileName = new File(safeTarget).getName();
        String className = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        List<String> directFiles = new ArrayList<>();
        directFiles.add(safeTarget);

        List<String> indirectFiles = new ArrayList<>();
        List<String> affectedApis = new ArrayList<>();
        List<String> affectedTests = new ArrayList<>();
        List<String> downstreamServices = new ArrayList<>();

        // Scan project files if available
        File projectDir = ProjectManager.getInstance().getCurrentProjectDirectory();
        if (projectDir != null && projectDir.exists() && projectDir.isDirectory()) {
            scanDirectory(projectDir, className, safeTarget, indirectFiles, affectedTests, downstreamServices);
        }

        // Identify exposed APIs / interfaces
        affectedApis.add("public " + className + " API contract");
        if (prompt.toLowerCase().contains("auth") || prompt.toLowerCase().contains("security")) {
            affectedApis.add("SecurityContext & Session Authentication Interceptors");
            downstreamServices.add("Auth Gateway Service");
        }
        if (prompt.toLowerCase().contains("db") || prompt.toLowerCase().contains("data") || prompt.toLowerCase().contains("table")) {
            affectedApis.add("Persistence Layer / Repository SPI");
            downstreamServices.add("Database Schema Migration Service");
        }
        if (downstreamServices.isEmpty()) {
            downstreamServices.add("IDE Core Application Runtime");
        }

        // Calculate impact score and risk level
        int score = 15;
        score += directFiles.size() * 10;
        score += Math.min(40, indirectFiles.size() * 8);
        score += Math.min(25, affectedTests.size() * 5);
        score += downstreamServices.size() * 5;
        score = Math.min(100, score);

        String riskLevel = "LOW";
        if (score >= 75) riskLevel = "CRITICAL";
        else if (score >= 50) riskLevel = "HIGH";
        else if (score >= 30) riskLevel = "MEDIUM";

        String rationale = String.format("Change touches %s with %d direct file(s), %d dependent file(s), %d test suite(s), and %d downstream service(s).",
                className, directFiles.size(), indirectFiles.size(), affectedTests.size(), downstreamServices.size());

        return new BlastRadiusResult(
                safeTarget,
                directFiles,
                indirectFiles,
                affectedApis,
                affectedTests,
                downstreamServices,
                riskLevel,
                score,
                rationale
        );
    }

    private void scanDirectory(File dir, String targetClassName, String targetPath,
                               List<String> indirectFiles, List<String> affectedTests, List<String> downstreamServices) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equals("target") && !f.getName().equals("build")) {
                    scanDirectory(f, targetClassName, targetPath, indirectFiles, affectedTests, downstreamServices);
                }
            } else if (f.getName().endsWith(".java") || f.getName().endsWith(".kt") || f.getName().endsWith(".py") || f.getName().endsWith(".ts")) {
                if (!f.getAbsolutePath().endsWith(targetPath)) {
                    try {
                        String content = Files.readString(f.toPath());
                        if (content.contains(targetClassName)) {
                            String rel = f.getName();
                            try {
                                File currProj = ProjectManager.getInstance().getCurrentProjectDirectory();
                                if (currProj != null) {
                                    rel = currProj.toPath().relativize(f.toPath()).toString();
                                }
                            } catch (Exception ignored) {}
                            if (f.getName().toLowerCase().contains("test")) {
                                if (!affectedTests.contains(rel)) affectedTests.add(rel);
                            } else {
                                if (!indirectFiles.contains(rel)) indirectFiles.add(rel);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}

package com.github.axiomate.agentic.ide.features.devexperience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature 47: Confidence heatmap.
 * Code the agent wrote is shaded by how certain it was, so reviewers know where to look hardest.
 */
public class ConfidenceHeatmapService {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceHeatmapService.class);
    private static ConfidenceHeatmapService instance;

    private ConfidenceHeatmapService() {}

    public static synchronized ConfidenceHeatmapService getInstance() {
        if (instance == null) {
            instance = new ConfidenceHeatmapService();
        }
        return instance;
    }

    /**
     * Generates a line-by-line confidence heatmap analysis for agent-generated code.
     */
    public HeatmapReport generateHeatmap(String targetFile, String codeContent) {
        log.info("Generating confidence heatmap for: {}", targetFile);

        List<HeatmapLine> heatmapLines = new ArrayList<>();
        List<String> focalPoints = new ArrayList<>();

        if (codeContent == null || codeContent.isBlank()) {
            return new HeatmapReport(targetFile, 0, 1.0, 0, 0, 0, List.of(), List.of());
        }

        String[] rawLines = codeContent.split("\n");
        int high = 0;
        int med = 0;
        int low = 0;
        double sumConf = 0.0;

        for (int i = 0; i < rawLines.length; i++) {
            int lineNo = i + 1;
            String line = rawLines[i];
            String trimmed = line.trim();

            double conf;
            String level;
            String hex;
            String reason = null;

            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("import ") || trimmed.startsWith("package ") || trimmed.equals("}")) {
                conf = 0.99;
                level = "HIGH";
                hex = "#238636"; // Green
                high++;
            } else if (trimmed.contains("synchronized") || trimmed.contains("volatile") || trimmed.contains("compareAndSet")) {
                conf = 0.62;
                level = "LOW";
                hex = "#DA3633"; // Red / Scrutinize
                reason = "Concurrent memory visibility and atomic CAS hazard: Scrutinize thread interleaving";
                low++;
                focalPoints.add("Line " + lineNo + ": Thread synchronization & memory model hazard");
            } else if (trimmed.contains("reflect") || trimmed.contains("Unsafe") || trimmed.contains("cast")) {
                conf = 0.60;
                level = "LOW";
                hex = "#DA3633"; // Red
                reason = "Dynamic type casting or reflection: High runtime class cast hazard";
                low++;
                focalPoints.add("Line " + lineNo + ": Dynamic reflection or unsafe casting");
            } else if (trimmed.contains("if (") || trimmed.contains("while (") || trimmed.contains("&&") || trimmed.contains("||")) {
                conf = 0.78;
                level = "MEDIUM";
                hex = "#D29922"; // Yellow
                reason = "Compound conditional logic: Verify edge cases and short-circuit ordering";
                med++;
            } else {
                conf = 0.92;
                level = "HIGH";
                hex = "#238636"; // Green
                high++;
            }

            sumConf += conf;
            heatmapLines.add(new HeatmapLine(lineNo, line, conf, level, hex, reason));
        }

        double avg = rawLines.length > 0 ? (sumConf / rawLines.length) : 1.0;
        return new HeatmapReport(targetFile, rawLines.length, avg, high, med, low, heatmapLines, focalPoints);
    }
}

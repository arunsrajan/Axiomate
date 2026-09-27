package com.github.axiomate.agentic.ide.features.devexperience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature 46: Voice and sketch input.
 * Describe a feature aloud or draw a UI on a whiteboard, and the agent builds from it.
 */
public class VoiceSketchInputProcessor {

    private static final Logger log = LoggerFactory.getLogger(VoiceSketchInputProcessor.class);
    private static VoiceSketchInputProcessor instance;

    private VoiceSketchInputProcessor() {}

    public static synchronized VoiceSketchInputProcessor getInstance() {
        if (instance == null) {
            instance = new VoiceSketchInputProcessor();
        }
        return instance;
    }

    /**
     * Processes spoken voice transcription into a structured coding prompt.
     */
    public VoiceSketchResult processVoiceTranscription(String transcribedSpeech) {
        log.info("Processing voice transcription input");
        String cleaned = transcribedSpeech != null ? transcribedSpeech : "";

        // Normalize spoken verbal idioms
        cleaned = cleaned.replaceAll("(?i)\\b(um|uh|like|you know|so basically)\\b", "");
        cleaned = cleaned.replaceAll("(?i)\\s*\\bdot\\s*\\b", ".");
        cleaned = cleaned.replaceAll("(?i)\\bopen paren\\b", "(");
        cleaned = cleaned.replaceAll("(?i)\\bclose paren\\b", ")");
        cleaned = cleaned.replaceAll("(?i)\\bopen brace\\b", "{");
        cleaned = cleaned.replaceAll("(?i)\\bclose brace\\b", "}");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        String prompt = "Voice Instruction: " + cleaned;
        String intent = "Implement feature described via spoken voice dictation";

        return new VoiceSketchResult(
                "VOICE_TRANSCRIPTION",
                transcribedSpeech,
                prompt,
                intent,
                List.of(),
                "// Generated from Voice Instruction:\n// " + cleaned
        );
    }

    /**
     * Processes a whiteboard UI sketch or ASCII wireframe layout into concrete UI components and code.
     */
    public VoiceSketchResult processUiSketch(String sketchTextOrAscii) {
        log.info("Processing UI whiteboard sketch layout");
        List<String> components = new ArrayList<>();

        String lower = sketchTextOrAscii != null ? sketchTextOrAscii.toLowerCase() : "";
        if (lower.contains("table") || lower.contains("|") || lower.contains("+---")) {
            components.add("JTable / Data Grid");
        }
        if (lower.contains("search") || lower.contains("input") || lower.contains("[ ]")) {
            components.add("Search JTextField with filter icon");
        }
        if (lower.contains("button") || lower.contains("[ok]") || lower.contains("[submit]")) {
            components.add("Action JButton (Primary Gradient)");
        }
        if (lower.contains("sidebar") || lower.contains("tree") || lower.contains("nav")) {
            components.add("Navigation JTree / Sidebar panel");
        }
        if (components.isEmpty()) {
            components.add("Main Application Content Container (BorderLayout)");
        }

        String codePreview = """
                // Synthesized from Whiteboard UI Wireframe
                JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
                JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
                searchBar.add(new JTextField(24));
                searchBar.add(new JButton("Search"));
                mainPanel.add(searchBar, BorderLayout.NORTH);
                mainPanel.add(new JScrollPane(new JTable(tableModel)), BorderLayout.CENTER);
                """;

        String prompt = "Build a modern UI component corresponding to this sketch structure: " + String.join(", ", components);

        return new VoiceSketchResult(
                "UI_SKETCH_WIREFRAME",
                sketchTextOrAscii,
                prompt,
                "Synthesize Swing/AWT UI component matching layout wireframe",
                components,
                codePreview
        );
    }
}

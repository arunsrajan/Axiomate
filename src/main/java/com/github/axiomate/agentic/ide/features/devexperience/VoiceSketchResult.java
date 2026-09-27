package com.github.axiomate.agentic.ide.features.devexperience;

import java.util.List;

/**
 * Result model produced from voice dictation or UI whiteboard sketch ingestion.
 */
public record VoiceSketchResult(
        String inputType, // "VOICE_TRANSCRIPTION", "UI_SKETCH_WIREFRAME", "MULTIMODAL"
        String rawInput,
        String synthesizedPrompt,
        String detectedIntent,
        List<String> inferredUiComponents,
        String generatedLayoutOrCodePreview
) {
}

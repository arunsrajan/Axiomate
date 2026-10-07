package com.github.axiomate.agentic.ide.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.vision.ImageAttachment;
import com.github.axiomate.agentic.ide.agent.vision.VisionSupport;
import com.github.axiomate.agentic.ide.util.ProjectManager;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Lets a vision model look at an image file (screenshots, mockups, diagrams, charts). Tool results are text,
 * so the image is queued and sent to the model in a user turn right after the tool results.
 */
public class ViewImageTool implements AgentTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "view_image";
    }

    @Override
    public String getDescription() {
        return """
            view_image: Look at an image file (png, jpg, jpeg, gif, webp, bmp) such as a screenshot, UI mockup,
            diagram or chart. The image is shown to you in the next message. Only works with vision models.
            Arguments JSON schema:
            {
              "path": "relative/or/absolute/path/to/image.png"
            }
            """;
    }

    @Override
    public String execute(String arguments) throws Exception {
        String pathStr;
        String trimmed = arguments == null ? "" : arguments.trim();
        if (trimmed.startsWith("{")) {
            JsonNode json = mapper.readTree(trimmed);
            pathStr = json.path("path").asText(json.path("file").asText(""));
        } else {
            pathStr = trimmed;
        }
        if (pathStr.isBlank()) return "ERROR: Provide the image path, e.g. {\"path\": \"docs/screenshot.png\"}";

        File base = ProjectManager.getInstance().getCurrentProjectDirectory();
        Path p = Paths.get(pathStr);
        File file = (p.isAbsolute() || base == null ? p : base.toPath().resolve(p)).normalize().toFile();
        if (!file.isFile()) return "ERROR: Image not found: " + file.getAbsolutePath();
        if (!VisionSupport.isImageFile(file)) {
            return "ERROR: Not an image file (supported: " + String.join(", ", VisionSupport.IMAGE_EXTENSIONS) + "): " + file.getName();
        }
        if (!VisionSupport.currentModelHasVision()) {
            return "ERROR: The current model cannot view images. Ask the user to switch to a vision model.";
        }
        ImageAttachment img = VisionSupport.fromFile(file);
        VisionSupport.queueForModel(img);
        return "Loaded " + VisionSupport.describe(java.util.List.of(img)) + ". The image follows in the next message.";
    }
}

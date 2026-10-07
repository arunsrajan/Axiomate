package com.github.axiomate.agentic.ide.agent.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.AgentMessage;
import com.github.axiomate.agentic.ide.agent.AgentRole;
import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import com.github.axiomate.agentic.ide.agent.tools.ViewImageTool;
import com.github.axiomate.agentic.ide.ui.components.FileMentionController;
import com.github.axiomate.agentic.ide.util.ProjectManager;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisionSupportTest {

    private File previousProject;

    @BeforeEach
    void remember() {
        previousProject = ProjectManager.getInstance().getCurrentProjectDirectory();
    }

    @AfterEach
    void restore() {
        VisionSupport.clearThreadState();
        if (previousProject != null) ProjectManager.getInstance().setCurrentProjectDirectory(previousProject);
    }

    static File png(Path dir, String name, int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        File f = dir.resolve(name).toFile();
        ImageIO.write(img, "png", f);
        return f;
    }

    @Test
    @DisplayName("Vision models are recognised from their ids; text-only models are not")
    void detectsVisionModels() {
        for (String id : List.of("claude-3-7-sonnet", "claude-sonnet-4-5", "claude-opus-4-1", "gpt-4o", "gpt-4.1-mini",
                "gpt-5", "o3", "gemini-2.5-pro", "llava:13b", "qwen2.5-vl-72b-instruct", "llama3.2-vision",
                "pixtral-12b", "gemma3:27b", "minicpm-v")) {
            assertTrue(VisionSupport.looksLikeVisionModel(id), id);
        }
        for (String id : List.of("deepseek-chat", "deepseek-reasoner", "gpt-3.5-turbo", "codellama:13b",
                "qwen2.5-coder:32b", "mistral-7b-instruct", "", "mock-agent")) {
            assertFalse(VisionSupport.looksLikeVisionModel(id), id);
        }
    }

    @Test
    @DisplayName("An explicit Vision setting on the model wins over detection, and a 'vision' tag enables it")
    void explicitSettingWins() {
        ModelDefinition off = new ModelDefinition("gpt-4o", "GPT-4o", 128_000, 4_096, List.of());
        off.setVision(false);
        ModelDefinition on = new ModelDefinition("my-local-model", "Local", 32_000, 4_096, List.of());
        on.setVision(true);
        ModelDefinition tagged = new ModelDefinition("custom-mm", "Custom", 32_000, 4_096, List.of("coding", "Vision"));
        ProviderConfig p = new ProviderConfig("P", "CUSTOM", "P", "http://x", "gpt-4o",
                new ArrayList<>(List.of(off, on, tagged)));
        assertFalse(VisionSupport.supportsVision(p, "gpt-4o"));
        assertTrue(VisionSupport.supportsVision(p, "my-local-model"));
        assertTrue(VisionSupport.supportsVision(p, "custom-mm"));
        assertTrue(VisionSupport.supportsVision(p, "claude-3-5-sonnet"), "not listed: detected from the id");
        assertFalse(VisionSupport.supportsVision(null, "deepseek-chat"));
    }

    @Test
    @DisplayName("Large images are scaled to the maximum edge; small ones are sent unchanged")
    void scalesLargeImages(@TempDir Path dir) throws Exception {
        File big = png(dir, "big.png", 4000, 2000);
        ImageAttachment a = VisionSupport.fromFile(big);
        assertEquals(VisionSupport.MAX_EDGE, a.width());
        assertEquals(784, a.height());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(a.base64Data())));
        assertEquals(VisionSupport.MAX_EDGE, decoded.getWidth());
        assertEquals(big.getAbsolutePath(), a.path(), "keeps the original file for replay");

        File small = png(dir, "small.png", 100, 50);
        ImageAttachment b = VisionSupport.fromFile(small);
        assertArrayEquals(Files.readAllBytes(small.toPath()), Base64.getDecoder().decode(b.base64Data()));
        assertEquals("image/png", b.mimeType());

        assertThrows(Exception.class, () -> VisionSupport.fromFile(Files.writeString(dir.resolve("a.txt"), "x").toFile()));
    }

    @Test
    @DisplayName("Pasted images are saved under .axiomate/attachments so sessions can show and replay them")
    void savesPastedImages(@TempDir Path dir) throws Exception {
        ImageAttachment pasted = VisionSupport.fromImage(new BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB), "pasted.png");
        assertNull(pasted.path());
        ImageAttachment saved = VisionSupport.saveToProject(pasted, dir.toFile());
        assertNotNull(saved.path());
        assertTrue(saved.path().startsWith(dir.resolve(".axiomate").resolve("attachments").toString()));
        assertEquals(30, ImageIO.read(new File(saved.path())).getWidth());
    }

    @Test
    @DisplayName("Multimodal contents put the text first, then one ImageContent per image")
    void buildsContents() {
        ImageAttachment img = new ImageAttachment("a.png", "image/png", "AAAA", null, 1, 1);
        List<Content> contents = VisionSupport.toContents("What is this?", List.of(img, img));
        assertEquals(3, contents.size());
        assertEquals("What is this?", ((TextContent) contents.get(0)).text());
        assertEquals("image/png", ((ImageContent) contents.get(1)).image().mimeType());
        assertEquals("AAAA", ((ImageContent) contents.get(2)).image().base64Data());
    }

    @Test
    @DisplayName("Message attachments survive a JSON round trip, and are omitted when empty")
    void attachmentsPersist() throws Exception {
        ObjectMapper m = new ObjectMapper();
        AgentMessage msg = new AgentMessage(AgentRole.USER, "look");
        msg.setAttachments(List.of("/p/.axiomate/attachments/x.png"));
        AgentMessage back = m.readValue(m.writeValueAsString(msg), AgentMessage.class);
        assertEquals(List.of("/p/.axiomate/attachments/x.png"), back.getAttachments());
        assertFalse(m.writeValueAsString(new AgentMessage(AgentRole.USER, "x")).contains("attachments"));
        assertTrue(m.readValue("{\"role\":\"USER\",\"content\":\"old\"}", AgentMessage.class).getAttachments().isEmpty(),
                "sessions saved before vision support still load");
    }

    @Test
    @DisplayName("view_image queues the image for vision models and refuses for text-only models")
    void viewImageTool(@TempDir Path dir) throws Exception {
        png(dir, "shot.png", 40, 30);
        ProjectManager.getInstance().setCurrentProjectDirectory(dir.toFile());
        ViewImageTool tool = new ViewImageTool();

        VisionSupport.setCurrentModelVision(false);
        assertTrue(tool.execute("{\"path\":\"shot.png\"}").startsWith("ERROR: The current model cannot view images"));
        assertTrue(VisionSupport.drainQueued().isEmpty());

        VisionSupport.setCurrentModelVision(true);
        String result = tool.execute("{\"path\":\"shot.png\"}");
        assertTrue(result.startsWith("Loaded shot.png (40×30)"), result);
        List<ImageAttachment> queued = VisionSupport.drainQueued();
        assertEquals(1, queued.size());
        assertTrue(VisionSupport.drainQueued().isEmpty(), "draining empties the queue");

        assertTrue(tool.execute("{\"path\":\"missing.png\"}").startsWith("ERROR: Image not found"));
        Files.writeString(dir.resolve("notes.txt"), "x");
        assertTrue(tool.execute("notes.txt").startsWith("ERROR: Not an image file"));
    }

    @Test
    @DisplayName("@-mentioned images become attachments instead of being read as text")
    void mentionedImages(@TempDir Path dir) throws Exception {
        png(dir, "ui.png", 10, 10);
        Files.writeString(dir.resolve("App.java"), "class App {}");
        String prompt = "Make @App.java match @ui.png please";
        List<File> images = FileMentionController.findMentionedImages(prompt, dir.toFile());
        assertEquals(List.of(dir.resolve("ui.png").toFile()), images);
        String context = FileMentionController.buildMentionedFilesContext(prompt, dir.toFile());
        assertTrue(context.contains("class App {}"));
        assertFalse(context.contains("ui.png"), "binary image data must not be pasted as text");
    }
}

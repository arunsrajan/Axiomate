package com.github.axiomate.agentic.ide.agent.vision;

import com.github.axiomate.agentic.ide.config.ModelDefinition;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Image handling for vision models: loading and downscaling images, deciding which models accept images,
 * and building multimodal message contents.
 */
public final class VisionSupport {

    /** Longest edge sent to a model. Larger images cost more tokens without helping most models. */
    public static final int MAX_EDGE = 1568;
    /** Encoded images above this size are re-encoded smaller (Anthropic rejects images over 5 MB). */
    public static final long MAX_BYTES = 3_500_000;
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    /**
     * Model ids known to accept images. Anything not matched is treated as text-only unless the model is
     * marked as vision-capable in Settings (Edit Model) or tagged "vision".
     */
    private static final Pattern VISION_MODELS = Pattern.compile(
            "claude-(3|sonnet-4|opus-4|haiku-4|sonnet-5|opus-5|haiku-5|fable)|claude-[a-z]+-[4-9]"
                    + "|gpt-4o|gpt-4\\.1|gpt-4-turbo|gpt-4-vision|gpt-5|chatgpt-4o|\\bo[134](-|$)|o[134]-mini"
                    + "|gemini|gemma-?3|llava|bakllava|vision|[-_.]vl\\b|-vl-|qwen2(\\.5)?-?vl|qvq|pixtral|mistral-(small|medium)-3"
                    + "|minicpm-v|moondream|internvl|phi-?[34](\\.5)?-vision|phi-4-multimodal|llama-?4|llama3\\.2-vision"
                    + "|grok-(2-)?vision|grok-4|kimi-vl|glm-4\\.?[15]?v|deepseek-vl|janus|molmo|granite3\\.2-vision",
            Pattern.CASE_INSENSITIVE);

    private static final ThreadLocal<List<ImageAttachment>> QUEUED = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean> CURRENT_MODEL_VISION = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private VisionSupport() {
    }

    // ------------------------------------------------------------------
    // Capability
    // ------------------------------------------------------------------

    /**
     * Whether a model accepts images. An explicit setting on the model wins, then a "vision" tag, then the
     * model id.
     */
    public static boolean supportsVision(ProviderConfig provider, String modelId) {
        ModelDefinition def = provider != null && modelId != null ? provider.findModel(modelId) : null;
        if (def != null && !def.getId().equalsIgnoreCase(modelId)) {
            def = null; // findModel falls back to the first model; its settings don't apply to this one
        }
        if (def != null && def.getVision() != null) return def.getVision();
        if (def != null && def.getTags() != null
                && def.getTags().stream().anyMatch(t -> t != null && t.equalsIgnoreCase("vision"))) {
            return true;
        }
        return looksLikeVisionModel(modelId);
    }

    public static boolean looksLikeVisionModel(String modelId) {
        return modelId != null && !modelId.isBlank() && VISION_MODELS.matcher(modelId).find();
    }

    /** Set by the agent loop so tools can tell whether the running model can see images. */
    public static void setCurrentModelVision(boolean vision) {
        CURRENT_MODEL_VISION.set(vision);
    }

    public static boolean currentModelHasVision() {
        return CURRENT_MODEL_VISION.get();
    }

    // ------------------------------------------------------------------
    // Images requested by tools (view_image) for the next model call
    // ------------------------------------------------------------------

    public static void queueForModel(ImageAttachment image) {
        QUEUED.get().add(image);
    }

    public static List<ImageAttachment> drainQueued() {
        List<ImageAttachment> out = new ArrayList<>(QUEUED.get());
        QUEUED.get().clear();
        return out;
    }

    public static void clearThreadState() {
        QUEUED.remove();
        CURRENT_MODEL_VISION.remove();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    public static boolean isImageFile(File f) {
        return f != null && IMAGE_EXTENSIONS.contains(extension(f.getName()));
    }

    /**
     * Loads an image file for a model. Small PNG/JPEG/GIF/WebP files are sent as they are; larger or other
     * formats are decoded, scaled to {@link #MAX_EDGE} and re-encoded.
     */
    public static ImageAttachment fromFile(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Not a file: " + file);
        String ext = extension(file.getName());
        if (!IMAGE_EXTENSIONS.contains(ext)) throw new IOException("Not a supported image type: " + file.getName());
        byte[] bytes = Files.readAllBytes(file.toPath());
        BufferedImage img = ImageIO.read(file);
        String mime = mimeFor(ext);
        if (img == null) {
            // WebP (and some GIFs) cannot be decoded by ImageIO; send them unchanged if small enough
            if (bytes.length > MAX_BYTES) throw new IOException("Image is too large to send: " + file.getName());
            return new ImageAttachment(file.getName(), mime, Base64.getEncoder().encodeToString(bytes),
                    file.getAbsolutePath(), 0, 0);
        }
        boolean passThrough = !"image/bmp".equals(mime) && bytes.length <= MAX_BYTES
                && Math.max(img.getWidth(), img.getHeight()) <= MAX_EDGE;
        if (passThrough) {
            return new ImageAttachment(file.getName(), mime, Base64.getEncoder().encodeToString(bytes),
                    file.getAbsolutePath(), img.getWidth(), img.getHeight());
        }
        ImageAttachment scaled = fromImage(img, file.getName());
        return new ImageAttachment(scaled.name(), scaled.mimeType(), scaled.base64Data(), file.getAbsolutePath(),
                scaled.width(), scaled.height());
    }

    /** Encodes an in-memory image (e.g. pasted from the clipboard), scaled to {@link #MAX_EDGE}. */
    public static ImageAttachment fromImage(java.awt.Image source, String name) throws IOException {
        BufferedImage img = toBuffered(source);
        img = scaleToFit(img, MAX_EDGE);
        byte[] png = encode(img, "png");
        if (png.length <= MAX_BYTES) {
            return new ImageAttachment(name, "image/png", Base64.getEncoder().encodeToString(png), null,
                    img.getWidth(), img.getHeight());
        }
        // Photos compress far better as JPEG
        byte[] jpg = encode(dropAlpha(img), "jpg");
        String jpgName = name.replaceAll("\\.[^.]+$", "") + ".jpg";
        return new ImageAttachment(jpgName, "image/jpeg", Base64.getEncoder().encodeToString(jpg), null,
                img.getWidth(), img.getHeight());
    }

    /**
     * Writes an attachment that has no file yet (a pasted image) to {@code .axiomate/attachments} in the project,
     * so the session can show and replay it later.
     */
    public static ImageAttachment saveToProject(ImageAttachment image, File projectDir) throws IOException {
        if (image.path() != null || projectDir == null) return image;
        Path dir = projectDir.toPath().resolve(".axiomate").resolve("attachments");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        String safe = image.name().replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = dir.resolve(stamp + "-" + safe);
        Files.write(target, Base64.getDecoder().decode(image.base64Data()));
        return new ImageAttachment(image.name(), image.mimeType(), image.base64Data(), target.toString(),
                image.width(), image.height());
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    /** Text followed by images, the order models expect for "look at this" prompts. */
    public static List<Content> toContents(String text, List<ImageAttachment> images) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text == null || text.isBlank() ? "(see attached image)" : text));
        if (images != null) {
            for (ImageAttachment img : images) {
                contents.add(ImageContent.from(Image.builder()
                        .base64Data(img.base64Data())
                        .mimeType(img.mimeType())
                        .build(), ImageContent.DetailLevel.AUTO));
            }
        }
        return contents;
    }

    public static String describe(List<ImageAttachment> images) {
        StringBuilder sb = new StringBuilder();
        for (ImageAttachment img : images) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(img.name());
            if (img.width() > 0) sb.append(" (").append(img.width()).append('×').append(img.height()).append(')');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String mimeFor(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    static BufferedImage scaleToFit(BufferedImage img, int maxEdge) {
        int w = img.getWidth(), h = img.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxEdge) return img;
        double s = (double) maxEdge / longest;
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toBuffered(java.awt.Image source) throws IOException {
        if (source instanceof BufferedImage b) return b;
        // Make sure the pixels are loaded (clipboard images may be produced lazily)
        java.awt.Image loaded = new javax.swing.ImageIcon(source).getImage();
        int w = loaded.getWidth(null), h = loaded.getHeight(null);
        if (w <= 0 || h <= 0) throw new IOException("Could not read the image");
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(loaded, 0, 0, null);
        g.dispose();
        return out;
    }

    private static BufferedImage dropAlpha(BufferedImage img) {
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, format, out)) throw new IOException("No encoder for " + format);
        return out.toByteArray();
    }
}

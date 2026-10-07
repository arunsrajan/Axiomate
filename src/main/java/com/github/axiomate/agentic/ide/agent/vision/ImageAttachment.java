package com.github.axiomate.agentic.ide.agent.vision;

/**
 * An image sent to a vision model: base64 data plus its MIME type. {@code path} is where the image is stored
 * on disk (pasted images are saved under the project's {@code .axiomate/attachments}), so sessions can
 * replay it without keeping the pixels in the session file.
 */
public record ImageAttachment(String name, String mimeType, String base64Data, String path, int width, int height) {

    /** Approximate size of the encoded image in bytes. */
    public long byteSize() {
        return base64Data == null ? 0 : (long) base64Data.length() * 3 / 4;
    }
}

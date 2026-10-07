package com.github.axiomate.agentic.ide.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Minimal Server-Sent Events reader: joins the {@code data:} lines of each event and hands the payload over.
 * Comments ({@code :}) and other fields ({@code event:}, {@code id:}) are ignored; the payloads carry their type.
 */
final class SseReader {

    private SseReader() {
    }

    /**
     * Reads events until the stream ends, {@code [DONE]} arrives, or {@code cancelled} returns true.
     *
     * @return false if reading stopped because of cancellation
     */
    static boolean read(InputStream in, BooleanSupplier cancelled, Consumer<String> onData) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.getAsBoolean()) return false;
                if (line.isEmpty()) {
                    if (!dispatch(data, onData)) return true;
                    continue;
                }
                if (line.startsWith(":")) continue;
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (!data.isEmpty()) data.append('\n');
                    data.append(value);
                }
            }
            dispatch(data, onData); // stream ended without a trailing blank line
            return !cancelled.getAsBoolean();
        }
    }

    /** @return false when the payload was the {@code [DONE]} terminator */
    private static boolean dispatch(StringBuilder data, Consumer<String> onData) {
        if (data.isEmpty()) return true;
        String payload = data.toString();
        data.setLength(0);
        if ("[DONE]".equals(payload.trim())) return false;
        onData.accept(payload);
        return true;
    }
}

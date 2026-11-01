package com.github.axiomate.agentic.ide.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.config.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The context window a server really runs a model with. Local servers (Colibri, vLLM, llama.cpp) start with a
 * context set on their command line, which can be smaller than the model's configured window: they report it in
 * GET /models, and name it in the error when a request is too long.
 */
final class ContextWindowProbe {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowProbe.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** Fields servers use for the window they serve, in /models entries. */
    private static final String[] WINDOW_FIELDS = {"context_length", "max_model_len", "context_window", "max_context_length"};
    private static final Pattern LIMIT = Pattern.compile("maximum context length is (\\d[\\d,]*) tokens");
    private static final Pattern USED = Pattern.compile("(?:resulted in|requested)(?: at least)? (\\d[\\d,]*) tokens");

    /** Server-reported windows by base URL and model; 0 when the server reports none. */
    private static final Map<String, Integer> known = new ConcurrentHashMap<>();

    private ContextWindowProbe() {
    }

    private static String key(ProviderConfig provider, String model) {
        return provider.getBaseUrl() + "|" + (model == null ? "" : model.toLowerCase());
    }

    /** The smaller of the configured window and the one the server reports (asked once per server and model). */
    static int window(ProviderConfig provider, String model, int configured) {
        if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) return configured;
        int reported = known.computeIfAbsent(key(provider, model), k -> fetch(provider, model));
        return reported > 0 && (configured <= 0 || reported < configured) ? reported : configured;
    }

    /** Remembers a window learned from a server's error, for the rest of this run. */
    static void remember(ProviderConfig provider, String model, int window) {
        if (provider != null && provider.getBaseUrl() != null && window > 0) known.put(key(provider, model), window);
    }

    static void forget() {
        known.clear();
    }

    private static int fetch(ProviderConfig provider, String model) {
        String base = provider.getBaseUrl().replaceAll("/+$", "");
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/models")).timeout(TIMEOUT).GET();
            if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
                req.header("Authorization", "Bearer " + provider.getApiKey());
            }
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
                    .send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return 0;
            return windowIn(MAPPER.readTree(res.body()), model);
        } catch (Exception e) {
            log.debug("Could not read the context window from {}/models: {}", base, e.getMessage());
            return 0;
        }
    }

    /** The window a /models response gives for {@code model} (or for its only model), or 0. */
    static int windowIn(JsonNode body, String model) {
        JsonNode data = body.path("data");
        if (!data.isArray()) return 0;
        JsonNode match = null;
        for (JsonNode m : data) {
            if (model != null && model.equalsIgnoreCase(m.path("id").asText())) match = m;
        }
        if (match == null && data.size() == 1) match = data.get(0);
        if (match == null) return 0;
        for (String field : WINDOW_FIELDS) {
            int v = match.path(field).asInt(0);
            if (v > 0) return v;
        }
        return 0;
    }

    /** The window named in a "maximum context length is N tokens" error anywhere in the cause chain, or 0. */
    static int limitIn(Throwable t) {
        return number(LIMIT, t);
    }

    /** How many tokens the server counted for the rejected request, or 0 when the error does not say. */
    static int usedIn(Throwable t) {
        return number(USED, t);
    }

    private static int number(Pattern p, Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() == null) continue;
            Matcher m = p.matcher(c.getMessage());
            if (m.find()) return Integer.parseInt(m.group(1).replace(",", ""));
        }
        return 0;
    }
}

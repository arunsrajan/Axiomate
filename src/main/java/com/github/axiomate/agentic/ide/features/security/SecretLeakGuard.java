package com.github.axiomate.agentic.ide.features.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature 32: Secret-leak guard.
 * The agent is blocked from reading, logging, or committing credentials.
 */
public class SecretLeakGuard {

    private static final Logger log = LoggerFactory.getLogger(SecretLeakGuard.class);
    private static SecretLeakGuard instance;

    /**
     * Token-shaped secrets must start at a token boundary: without it "task-scheduler-configuration" matched the
     * OpenAI pattern ("sk-scheduler-...") and ordinary identifiers were masked in prompts and files.
     */
    private static final String START = "(?<![A-Za-z0-9_-])";

    private static final List<SecretPattern> SECRET_PATTERNS = List.of(
            new SecretPattern("ANTHROPIC_KEY", Pattern.compile(START + "sk-ant-[a-zA-Z0-9_-]{20,}")),
            new SecretPattern("OPENAI_KEY", Pattern.compile(START + "sk-(?!ant-)(?:proj-)?[a-zA-Z0-9_\\-]{20,}")),
            new SecretPattern("AWS_ACCESS_KEY", Pattern.compile(START + "AKIA[0-9A-Z]{16}(?![A-Za-z0-9])")),
            new SecretPattern("GITHUB_PAT", Pattern.compile(START + "gh[pousr]_[a-zA-Z0-9]{36,}")),
            new SecretPattern("GENERIC_API_KEY", Pattern.compile("(?i)(?:api_key|apikey|secret_key|private_key)\\s*[:=]\\s*['\"][a-zA-Z0-9_\\-]{16,}['\"]")),
            new SecretPattern("RSA_PRIVATE_KEY", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new SecretPattern("JWT_TOKEN", Pattern.compile(START + "ey[A-Za-z0-9_-]{10,}\\.ey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"))
    );

    private record SecretPattern(String type, Pattern pattern) {}

    private SecretLeakGuard() {}

    public static synchronized SecretLeakGuard getInstance() {
        if (instance == null) {
            instance = new SecretLeakGuard();
        }
        return instance;
    }

    /**
     * Scans text for credentials and returns scan details along with a redacted safe string.
     */
    public LeakScanResult scanAndSanitize(String input) {
        if (input == null || input.isBlank()) {
            return new LeakScanResult(false, 0, List.of(), input);
        }

        List<LeakScanResult.DetectedSecret> detected = new ArrayList<>();
        String sanitized = input;

        for (SecretPattern sp : SECRET_PATTERNS) {
            Matcher m = sp.pattern().matcher(input);
            while (m.find()) {
                String match = m.group();
                String masked = maskSecret(match);
                detected.add(new LeakScanResult.DetectedSecret(sp.type(), masked, m.start(), m.end()));
                sanitized = sanitized.replace(match, masked);
            }
        }

        boolean found = !detected.isEmpty();
        if (found) {
            log.warn("Secret-leak guard blocked {} credential(s) from exposure", detected.size());
        }

        return new LeakScanResult(found, detected.size(), detected, sanitized);
    }

    private String maskSecret(String raw) {
        if (raw == null || raw.length() <= 8) return "[REDACTED_SECRET]";
        return raw.substring(0, 4) + "..." + raw.substring(raw.length() - 4) + " [REDACTED]";
    }
}

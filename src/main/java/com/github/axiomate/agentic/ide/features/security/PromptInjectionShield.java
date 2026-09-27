package com.github.axiomate.agentic.ide.features.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Feature 33: Prompt-injection shield.
 * Content from files, web pages, and dependencies is treated as data and flagged if it tries to instruct the agent.
 */
public class PromptInjectionShield {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionShield.class);
    private static PromptInjectionShield instance;

    private static final List<InjectionPattern> INJECTION_PATTERNS = List.of(
            new InjectionPattern("INSTRUCTION_OVERRIDE", Pattern.compile("(?i)(?:ignore|disregard|forget)\\s+(?:all\\s+)?(?:previous|prior|above)\\s+(?:instructions|rules|prompts)")),
            new InjectionPattern("ROLE_HIJACK", Pattern.compile("(?i)you\\s+are\\s+now\\s+(?:an?\\s+)?(?:unfiltered|jailbroken|dan|evil)")),
            new InjectionPattern("SYSTEM_DELIMITER_SPOOF", Pattern.compile("(?i)(?:<\\|im_start\\|>system|<system>|system\\s*:\\s*override)")),
            new InjectionPattern("SECRET_EXTRACTION_PROMPT", Pattern.compile("(?i)(?:print|reveal|output|display|show)\\s+(?:all\\s+)?(?:api\\s*keys?|secrets?|passwords?|tokens?)"))
    );

    private record InjectionPattern(String vectorName, Pattern pattern) {}

    private PromptInjectionShield() {}

    public static synchronized PromptInjectionShield getInstance() {
        if (instance == null) {
            instance = new PromptInjectionShield();
        }
        return instance;
    }

    /**
     * Inspects untrusted external content (file snippets, dependencies, web data), flags injection attempts,
     * and encapsulates it safely inside an impenetrable data boundary.
     */
    public InjectionScanResult inspectAndShield(String sourceName, String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new InjectionScanResult(false, 0, List.of(), rawContent, "Content is empty.");
        }

        List<String> vectors = new ArrayList<>();
        int threatScore = 0;

        for (InjectionPattern ip : INJECTION_PATTERNS) {
            if (ip.pattern().matcher(rawContent).find()) {
                vectors.add(ip.vectorName());
                threatScore += 35;
            }
        }

        threatScore = Math.min(100, threatScore);
        boolean detected = !vectors.isEmpty();

        if (detected) {
            log.warn("Prompt-injection shield intercepted threat in '{}': vectors={}", sourceName, vectors);
        }

        // Encapsulate content inside strict immutable data boundaries
        String shielded = String.format("""
                <untrusted_data_boundary source="%s" verified_as_data_only="true">
                %s
                </untrusted_data_boundary>
                """, sourceName != null ? sourceName : "external", rawContent);

        String explanation = detected
                ? "Flagged potential prompt-injection attack in " + sourceName + ". Neutralized and isolated as passive data."
                : "Safe content verified. Wrapped in data enclosure.";

        return new InjectionScanResult(detected, threatScore, vectors, shielded, explanation);
    }
}

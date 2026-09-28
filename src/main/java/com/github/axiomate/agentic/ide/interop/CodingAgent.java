package com.github.axiomate.agentic.ide.interop;

import java.util.Optional;

/**
 * External coding agents whose memory, rules, sessions and MCP configuration Axiomate can
 * import from and export to.
 */
public enum CodingAgent {
    CLAUDE_CODE("claude-code", "Claude Code", "Anthropic"),
    CODEX("codex", "OpenAI Codex", "OpenAI"),
    CURSOR("cursor", "Cursor", "Anysphere"),
    ANTIGRAVITY("antigravity", "Google Antigravity", "Google"),
    GEMINI_CLI("gemini-cli", "Gemini CLI", "Google"),
    WINDSURF("windsurf", "Windsurf", "Cognition"),
    GITHUB_COPILOT("copilot", "GitHub Copilot", "GitHub"),
    CLINE("cline", "Cline", "Cline"),
    ROO_CODE("roo-code", "Roo Code", "Roo Code"),
    KIRO("kiro", "Kiro", "AWS");

    private final String id;
    private final String displayName;
    private final String vendor;

    CodingAgent(String id, String displayName, String vendor) {
        this.id = id;
        this.displayName = displayName;
        this.vendor = vendor;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getVendor() {
        return vendor;
    }

    public static Optional<CodingAgent> fromId(String id) {
        if (id == null) return Optional.empty();
        for (CodingAgent a : values()) {
            if (a.id.equalsIgnoreCase(id) || a.name().equalsIgnoreCase(id)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return displayName;
    }
}

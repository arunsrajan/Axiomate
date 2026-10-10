package com.github.axiomate.agentic.ide.interop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InteropFixesTest {

    @Test
    @DisplayName("Exported memory keeps a Windows CLAUDE.md in CRLF and ignores out-of-order markers")
    void managedBlockEdgeCases() {
        String windows = "# Team rules\r\nUse tabs.\r\n";
        String out = MarkdownDocument.upsertManagedBlock(windows, "- prefer records");
        assertTrue(out.startsWith("# Team rules\r\nUse tabs.\r\n"), out);
        assertFalse(out.replace("\r\n", "").contains("\n"), "no bare LF introduced: " + out);

        String odd = "notes " + MarkdownDocument.MANAGED_END + " then " + MarkdownDocument.MANAGED_START + "\n";
        String merged = MarkdownDocument.upsertManagedBlock(odd, "- rule");
        assertTrue(merged.startsWith("notes "), "user text before a stray end marker is kept: " + merged);
        assertTrue(merged.contains("- rule"));
    }

    @Test
    @DisplayName("Codex TOML: overwrite replaces the server's tables, others are untouched")
    void removeTomlServer() {
        String toml = """
                model = "o3"

                [mcp_servers.github]
                command = "npx"
                args = ["-y", "gh"]

                [mcp_servers.github.env]
                TOKEN = "x"

                [mcp_servers."docs"]
                command = "uvx"
                """;
        String out = McpConfigInterop.removeTomlServer(toml, "github");
        assertFalse(out.contains("[mcp_servers.github"), out);
        assertFalse(out.contains("TOKEN"), out);
        assertTrue(out.contains("model = \"o3\""));
        assertTrue(out.contains("[mcp_servers.\"docs\"]") && out.contains("uvx"));
        assertTrue(McpConfigInterop.removeTomlServer(toml, "docs").contains("[mcp_servers.github]"));
    }
}

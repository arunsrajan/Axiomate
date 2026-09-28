package com.github.axiomate.agentic.ide.plugins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Registry entry for an installed plugin, persisted in {@code ~/.axiomate-ide/plugins/installed.json}.
 *
 * @param mcpEnabled     whether the plugin's MCP servers should run while the plugin is enabled
 * @param source         where it was installed from: "catalog", a folder path, a ZIP path or a URL
 * @param mcpServerNames names of the MCP server entries this plugin owns
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledPlugin(PluginManifest manifest, boolean enabled, boolean mcpEnabled, String source,
                              String format, String installPath, String installedAt, List<String> mcpServerNames) {

    public InstalledPlugin {
        mcpServerNames = mcpServerNames != null ? List.copyOf(mcpServerNames) : List.of();
    }

    public String id() {
        return manifest.id();
    }

    public InstalledPlugin withEnabled(boolean value) {
        return new InstalledPlugin(manifest, value, mcpEnabled, source, format, installPath, installedAt, mcpServerNames);
    }

    public InstalledPlugin withMcpEnabled(boolean value) {
        return new InstalledPlugin(manifest, enabled, value, source, format, installPath, installedAt, mcpServerNames);
    }
}

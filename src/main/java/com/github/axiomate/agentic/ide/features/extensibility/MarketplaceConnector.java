package com.github.axiomate.agentic.ide.features.extensibility;

import java.util.List;

/**
 * Model representing a plugin tool/connector available in the marketplace.
 */
public record MarketplaceConnector(
        String id,
        String name,
        String category, // "ISSUE_TRACKING", "DESIGN", "MONITORING", "CLOUD", "API"
        String providerName,
        String description,
        String version,
        boolean installed,
        boolean enabled,
        List<String> exposedTools,
        String protocolType // "MCP", "REST_OPENAPI", "NATIVE_PLUGIN"
) {
    public MarketplaceConnector withStatus(boolean installed, boolean enabled) {
        return new MarketplaceConnector(id, name, category, providerName, description, version, installed, enabled, exposedTools, protocolType);
    }
}

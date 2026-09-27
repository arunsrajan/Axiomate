package com.github.axiomate.agentic.ide.features.codeunderstanding;

/**
 * An architectural layering rule defining forbidden package dependencies or design constraints.
 */
public record LayerRule(
        String id,
        String name,
        String fromLayerPackage, // e.g. "..ui.."
        String forbiddenToLayerPackage, // e.g. "..memory.." (UI cannot bypass agent service to touch storage directly)
        String rationale
) {
}

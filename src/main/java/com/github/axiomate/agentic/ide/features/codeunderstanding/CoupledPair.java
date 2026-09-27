package com.github.axiomate.agentic.ide.features.codeunderstanding;

/**
 * Model representing two implicitly coupled files.
 */
public record CoupledPair(
        String fileA,
        String fileB,
        double couplingConfidence, // 0.0 - 1.0 (e.g. 0.94 = 94% co-change rate)
        int coChangeCount,
        String couplingType,       // e.g. "CONFIG_CODE", "INTERFACE_TEST", "DTO_SERIALIZER", "SCHEMA_MIGRATION"
        String explanation
) {
}

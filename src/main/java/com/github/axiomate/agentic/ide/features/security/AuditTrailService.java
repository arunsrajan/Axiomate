package com.github.axiomate.agentic.ide.features.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Feature 36: Full audit trail.
 * A signed, searchable log records every agent action, tool call, and reasoning summary for compliance.
 */
public class AuditTrailService {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailService.class);
    private static AuditTrailService instance;

    private final List<AuditEntry> auditLog = new CopyOnWriteArrayList<>();
    private String lastHash = "0000000000000000000000000000000000000000000000000000000000000000";

    private AuditTrailService() {
        recordEvent("SYSTEM_INIT", "SYSTEM", "Axiomate IDE", "Audit trail engine initialized with SHA-256 chain");
    }

    public static synchronized AuditTrailService getInstance() {
        if (instance == null) {
            instance = new AuditTrailService();
        }
        return instance;
    }

    public synchronized AuditEntry recordEvent(String actionType, String actor, String targetResource, String details) {
        return recordEvent("session-default", actionType, actor, targetResource, details);
    }

    public synchronized AuditEntry recordEvent(String sessionId, String actionType, String actor, String targetResource, String details) {
        String id = "aud-" + System.currentTimeMillis() + "-" + (auditLog.size() + 1);
        Instant now = Instant.now();
        String payload = id + "|" + now.toString() + "|" + sessionId + "|" + actionType + "|" + actor + "|" + targetResource + "|" + details + "|" + lastHash;
        String signature = computeSha256(payload);

        AuditEntry entry = new AuditEntry(id, now, sessionId, actionType, actor, targetResource, details, signature, lastHash);
        auditLog.add(entry);
        this.lastHash = signature;

        log.debug("Audit recorded: {} [{}] by {} on {}", id, actionType, actor, targetResource);
        return entry;
    }

    public List<AuditEntry> getAllEntries() {
        return Collections.unmodifiableList(auditLog);
    }

    public List<AuditEntry> search(String query) {
        if (query == null || query.isBlank()) return getAllEntries();
        String q = query.toLowerCase();
        return auditLog.stream()
                .filter(e -> e.actionType().toLowerCase().contains(q) ||
                        e.targetResource().toLowerCase().contains(q) ||
                        e.detailsSummary().toLowerCase().contains(q) ||
                        e.actor().toLowerCase().contains(q))
                .collect(Collectors.toList());
    }

    public boolean verifyIntegrity() {
        String currentHash = "0000000000000000000000000000000000000000000000000000000000000000";
        for (AuditEntry e : auditLog) {
            if (e.actionType().equals("SYSTEM_INIT")) {
                currentHash = e.sha256Signature();
                continue;
            }
            if (!e.previousHashChain().equals(currentHash)) {
                log.error("Audit trail integrity failed at entry {}", e.auditId());
                return false;
            }
            String payload = e.auditId() + "|" + e.timestamp().toString() + "|" + e.sessionId() + "|" +
                    e.actionType() + "|" + e.actor() + "|" + e.targetResource() + "|" + e.detailsSummary() + "|" + currentHash;
            String expected = computeSha256(payload);
            if (!expected.equals(e.sha256Signature())) {
                log.error("Audit trail signature mismatch at entry {}", e.auditId());
                return false;
            }
            currentHash = e.sha256Signature();
        }
        return true;
    }

    private String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}

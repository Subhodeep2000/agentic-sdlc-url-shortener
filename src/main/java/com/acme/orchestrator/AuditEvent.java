package com.acme.orchestrator;

import java.util.Map;

/** One entry of the tamper-evident audit chain. */
public record AuditEvent(long seq, String timestamp, String runId, String type, String taskId, String actor,
                         Map<String, Object> details, String prevHash, String hash) {
}

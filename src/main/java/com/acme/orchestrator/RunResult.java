package com.acme.orchestrator;

import java.util.List;
import java.util.Map;

public record RunResult(String runId, RunStatus status, String stopReason, Map<String, TaskStatus> taskStatuses,
                        Map<String, String> taskNotes, Blackboard blackboard, Lineage lineage, AuditLog audit,
                        Map<String, Object> metrics, Plan plan, List<String> completionOrder) {
    public long metricLong(String k) {
        return ((Number) metrics.get(k)).longValue();
    }

    public double metricDouble(String k) {
        return ((Number) metrics.get(k)).doubleValue();
    }
}

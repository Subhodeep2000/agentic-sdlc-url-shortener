package com.acme.orchestrator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reliability metrics. successRate = SUCCEEDED / (SUCCEEDED+FAILED+REJECTED+ROLLED_BACK+INVALIDATED), skipped tasks
 * reported separately; retryRate = retries / tasks executed; MTTR = mean time from a task's first failed attempt to its
 * eventual success (recovered incidents only); endToEndMillis = wall clock; parallelism = sum(task time) / wall time.
 */
public final class Metrics {
    private long runStart;
    private long runEnd;
    private int attempts;
    private int failedAttempts;
    private int retries;
    private int fallbacks;
    private int rollbacks;
    private int replans;
    private final Map<String, Integer> approvals = new LinkedHashMap<>();
    private final Map<PolicyCategory, Integer> violations = new EnumMap<>(PolicyCategory.class);
    private final List<Long> recoveries = new ArrayList<>();
    private final Map<String, Long> taskMillis = new LinkedHashMap<>();
    private final Map<Stage, long[]> stageWindow = new EnumMap<>(Stage.class);
    private final Map<String, Integer> executed = new LinkedHashMap<>();

    synchronized void runStarted(long t) {
        runStart = t;
    }

    synchronized void runEnded(long t) {
        runEnd = t;
    }

    synchronized void attempt(String task, boolean ok, boolean retry, boolean fallback) {
        attempts++;
        failedAttempts += ok ? 0 : 1;
        retries += retry ? 1 : 0;
        fallbacks += fallback ? 1 : 0;
        executed.merge(task, 1, Integer::sum);
    }

    synchronized void taskTime(String task, Stage s, long start, long end) {
        taskMillis.merge(task, Math.max(0, end - start), Long::sum);
        long[] w = stageWindow.computeIfAbsent(s, k -> new long[] {Long.MAX_VALUE, Long.MIN_VALUE});
        w[0] = Math.min(w[0], start);
        w[1] = Math.max(w[1], end);
    }

    synchronized void rollback() {
        rollbacks++;
    }

    synchronized void replan() {
        replans++;
    }

    synchronized void violation(PolicyCategory c) {
        violations.merge(c, 1, Integer::sum);
    }

    synchronized void approval(String kind) {
        approvals.merge(kind, 1, Integer::sum);
    }

    synchronized void recovered(long millis) {
        recoveries.add(millis);
    }

    synchronized int totalAttempts() {
        return attempts;
    }

    synchronized Map<String, Object> report(Map<String, TaskStatus> status) {
        Map<TaskStatus, Integer> c = new EnumMap<>(TaskStatus.class);
        status.values().forEach(s -> c.merge(s, 1, Integer::sum));
        int ok = c.getOrDefault(TaskStatus.SUCCEEDED, 0);
        int denom = ok + c.getOrDefault(TaskStatus.FAILED, 0) + c.getOrDefault(TaskStatus.REJECTED, 0)
                + c.getOrDefault(TaskStatus.ROLLED_BACK, 0) + c.getOrDefault(TaskStatus.INVALIDATED, 0);
        int ran = executed.size();
        long wall = Math.max(0, runEnd - runStart);
        long sum = taskMillis.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tasksTotal", (long) status.size());
        for (TaskStatus s : TaskStatus.values()) {
            if (s != TaskStatus.PENDING && s != TaskStatus.RUNNING) {
                m.put("tasks." + s.name().toLowerCase(), (long) c.getOrDefault(s, 0));
            }
        }
        m.put("successRate", denom == 0 ? Double.NaN : (double) ok / denom);
        m.put("attempts", (long) attempts);
        m.put("failedAttempts", (long) failedAttempts);
        m.put("retries", (long) retries);
        m.put("retryRate", ran == 0 ? Double.NaN : (double) retries / ran);
        m.put("fallbacksUsed", (long) fallbacks);
        m.put("rollbackActions", (long) rollbacks);
        m.put("rollbackRate", ran == 0 ? Double.NaN : (double) rollbacks / ran);
        m.put("replans", (long) replans);
        m.put("mttrMillis", recoveries.isEmpty() ? Double.NaN
                : recoveries.stream().mapToLong(Long::longValue).average().orElse(Double.NaN));
        m.put("incidentsRecovered", (long) recoveries.size());
        for (PolicyCategory p : PolicyCategory.values()) {
            m.put("policyViolations." + p.name().toLowerCase(), (long) violations.getOrDefault(p, 0));
        }
        for (String k : List.of("requested", "granted", "rejected", "reused")) {
            m.put("approvals." + k, (long) approvals.getOrDefault(k, 0));
        }
        m.put("endToEndMillis", wall);
        m.put("sumTaskMillis", sum);
        m.put("parallelism", wall == 0 ? Double.NaN : (double) sum / wall);
        return m;
    }
}

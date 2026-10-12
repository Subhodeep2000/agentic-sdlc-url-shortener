package com.acme.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The agent's only window onto the system. Reads are limited to declared inputs and pinned to the version first seen,
 * so the engine can later detect that upstream moved on while the task was running.
 */
public final class TaskContext {
    private final TaskSpec spec;
    private final int attempt;
    private final String agentName;
    private final Blackboard bb;
    private final Map<String, Object> resources;
    private final AuditLog audit;
    private final BooleanSupplier cancelled;
    private final Map<String, ArtifactVersion> pinned = new ConcurrentHashMap<>();

    TaskContext(TaskSpec spec, int attempt, String agentName, Blackboard bb, Map<String, Object> resources,
                AuditLog audit, BooleanSupplier cancelled) {
        this.spec = spec;
        this.attempt = attempt;
        this.agentName = agentName;
        this.bb = bb;
        this.resources = resources;
        this.audit = audit;
        this.cancelled = cancelled;
    }

    public String taskId() {
        return spec.id();
    }

    public int attempt() {
        return attempt;
    }

    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    public Object read(String key) {
        return readOptional(key).orElseThrow(
                () -> new IllegalStateException("artifact '" + key + "' not available to task " + spec.id()));
    }

    public Optional<Object> readOptional(String key) {
        if (!spec.reads().contains(key)) {
            throw new AutonomyViolationException("task " + spec.id() + " tried to read undeclared artifact '" + key + "'");
        }
        ArtifactVersion v = pinned.computeIfAbsent(key, k -> bb.latest(k).orElse(null));
        return Optional.ofNullable(v).map(ArtifactVersion::value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String key) {
        return (Map<String, Object>) read(key);
    }

    public String readString(String key) {
        return (String) read(key);
    }

    /** Engine resource (e.g. the workspace); denied unless the task holds the capability. */
    public <T> T resource(String name, Class<T> type, Capability required) {
        boolean ok = spec.capabilities().contains(required)
                || (required == Capability.READ_WORKSPACE && spec.capabilities().contains(Capability.WRITE_WORKSPACE));
        if (!ok) {
            throw new AutonomyViolationException("task " + spec.id() + " lacks capability " + required);
        }
        Object r = resources.get(name);
        if (r == null) {
            throw new IllegalStateException("resource '" + name + "' not configured");
        }
        return type.cast(r);
    }

    public void log(String message) {
        audit.append("AGENT_LOG", spec.id(), "agent:" + agentName, Map.of("attempt", attempt, "message", message));
    }

    Map<String, Integer> consumed() {
        Map<String, Integer> m = new LinkedHashMap<>();
        pinned.forEach((k, v) -> m.put(k, v.version()));
        return m;
    }
}

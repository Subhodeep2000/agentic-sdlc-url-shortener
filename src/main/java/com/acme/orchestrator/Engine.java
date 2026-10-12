package com.acme.orchestrator;

import com.acme.common.Hashing;
import com.acme.common.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stateful, graph-driven workflow engine.
 *
 * <p>Threading: one <em>coordinator</em> thread owns all state transitions and artifact commits; worker threads each
 * run one task (gates, policies, approval, agent attempts) and return an immutable {@link Outcome}. Independent tasks
 * run concurrently; joins are plain dependencies. Commit order, and therefore lineage, is deterministic per outcome.
 */
public final class Engine {
    private enum Kind { SUCCESS, FAILED, REJECTED, CANCELLED, SAFE_STOP }

    private record Outcome(String taskId, int generation, Kind kind, AgentOutput output, Map<String, Integer> consumed,
                           Agent agent, TaskContext ctx, int attempt, String note, long startMs, long endMs,
                           long firstFailureMs) {
    }

    private record Change(String key, Object value, String author) {
    }

    private final EngineConfig cfg;
    private final AgentRegistry agents;
    private final Approval.Handler approvals;
    private volatile RunState current;

    public Engine(EngineConfig cfg, AgentRegistry agents, Approval.Handler approvals) {
        this.cfg = cfg;
        this.agents = agents;
        this.approvals = approvals;
    }

    /** Kill switch for the run in progress. Safe to call from any thread. */
    public void safeStop(String reason) {
        RunState rs = current;
        if (rs != null) {
            rs.safeStop(reason);
        }
    }

    public RunResult run(Plan planIn, Map<String, Object> inputs, RunHook hook) {
        RunState rs = new RunState(planIn.copy(), hook == null ? new RunHook() { } : hook);
        current = rs;
        rs.metrics.runStarted(cfg.clock.millis());
        rs.audit.append("RUN_STARTED", null, "engine", Map.of("tasks", (long) rs.plan.tasks().size(),
                "parallelism", (long) cfg.parallelism, "maxReplans", (long) cfg.maxReplans,
                "maxTotalAttempts", (long) cfg.maxTotalAttempts));
        for (TaskSpec t : rs.plan.tasks()) {
            rs.status.put(t.id(), TaskStatus.PENDING);
            rs.gen.put(t.id(), 0);
        }
        String problem = validatePlan(rs);
        if (problem != null) {
            rs.audit.append("PLAN_REJECTED", null, "engine", Map.of("reason", problem));
            rs.stopReason = "plan rejected: " + problem;
            rs.rejectedPlan = true;
            return finish(rs);
        }
        rs.audit.append("PLAN_VALIDATED", null, "engine", Map.of("order", rs.plan.topologicalOrder()));
        inputs.forEach((k, v) -> {
            ArtifactVersion av = rs.bb.put(k, v, "input");
            rs.audit.append("INPUT_RECORDED", null, "input", Map.of("artifact", av.ref(), "hash", av.hash()));
        });
        return execute(rs);
    }

    // ---- coordinator ----------------------------------------------------------------------------------------

    private RunResult execute(RunState rs) {
        ExecutorService pool = Executors.newFixedThreadPool(cfg.parallelism, r -> {
            Thread t = new Thread(r, "engine-worker");
            t.setDaemon(true);
            return t;
        });
        ExecutorCompletionService<Outcome> cs = new ExecutorCompletionService<>(pool);
        int running = 0;
        long deadlineAt = cfg.clock.millis() + cfg.deadline.toMillis();
        try {
            while (true) {
                drainChanges(rs);
                if (!rs.stopped.get() && cfg.clock.millis() > deadlineAt) {
                    rs.safeStop("deadline of " + cfg.deadline + " exceeded");
                }
                if (!rs.stopped.get() && rs.metrics.totalAttempts() >= cfg.maxTotalAttempts) {
                    rs.safeStop("attempt budget of " + cfg.maxTotalAttempts + " exhausted");
                }
                propagateBlocked(rs);
                int launched = 0;
                if (!rs.stopped.get()) {
                    for (TaskSpec t : rs.plan.tasks()) {
                        if (running >= cfg.parallelism) {
                            break;
                        }
                        if (isReady(rs, t)) {
                            rs.status.put(t.id(), TaskStatus.RUNNING);
                            rs.locksHeld.addAll(t.locks());
                            int g = rs.gen.get(t.id());
                            cs.submit(() -> work(rs, t, g));
                            running++;
                            launched++;
                        }
                    }
                }
                if (running == 0 && launched == 0 && rs.changes.isEmpty()) {
                    break;
                }
                Future<Outcome> f = cs.poll(50, TimeUnit.MILLISECONDS);
                if (f != null) {
                    running--;
                    onOutcome(rs, f.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rs.safeStop("coordinator interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            rs.safeStop("engine failure: " + e.getCause());
        } finally {
            pool.shutdownNow();
        }
        return finish(rs);
    }

    private boolean isReady(RunState rs, TaskSpec t) {
        if (rs.status.get(t.id()) != TaskStatus.PENDING) {
            return false;
        }
        for (String d : t.dependsOn()) {
            if (rs.status.get(d) != TaskStatus.SUCCEEDED) {
                return false;
            }
        }
        return t.locks().stream().noneMatch(rs.locksHeld::contains);
    }

    /** Failure containment: PENDING tasks whose dependencies can no longer succeed are SKIPPED. */
    private void propagateBlocked(RunState rs) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TaskSpec t : rs.plan.tasks()) {
                if (rs.status.get(t.id()) != TaskStatus.PENDING) {
                    continue;
                }
                for (String d : t.dependsOn()) {
                    TaskStatus s = rs.status.get(d);
                    if (s != TaskStatus.SUCCEEDED && s != TaskStatus.PENDING && s != TaskStatus.RUNNING) {
                        rs.status.put(t.id(), TaskStatus.SKIPPED);
                        rs.notes.put(t.id(), "blocked by " + d + " (" + s + ")");
                        rs.audit.append("TASK_SKIPPED", t.id(), "engine", Map.of("blockedBy", d, "state", s.name()));
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private void drainChanges(RunState rs) {
        Change c;
        while ((c = rs.changes.poll()) != null) {
            ArtifactVersion av = rs.bb.put(c.key, c.value, "external:" + c.author);
            rs.audit.append("EXTERNAL_CHANGE", null, "human:" + c.author,
                    Map.of("artifact", av.ref(), "hash", av.hash()));
            invalidateStale(rs, Set.of(c.key), "external change to " + av.ref() + " by " + c.author);
        }
    }

    private void onOutcome(RunState rs, Outcome o) {
        TaskSpec spec = rs.plan.get(o.taskId);
        rs.locksHeld.removeAll(spec.locks());
        rs.metrics.taskTime(o.taskId, spec.stage(), o.startMs, o.endMs);
        if (o.generation != rs.gen.get(o.taskId)) {
            discard(rs, o, "superseded by re-plan");
            rs.status.put(o.taskId, TaskStatus.PENDING);
            return;
        }
        switch (o.kind) {
            case SUCCESS -> {
                for (Map.Entry<String, Integer> e : o.consumed.entrySet()) {
                    if (rs.bb.latestVersion(e.getKey()) > e.getValue()) {
                        discard(rs, o, "input " + e.getKey() + " changed during execution");
                        rs.status.put(o.taskId, TaskStatus.PENDING);
                        return;
                    }
                }
                commit(rs, spec, o);
            }
            case FAILED -> failTask(rs, spec, o);
            case REJECTED -> {
                rs.status.put(o.taskId, TaskStatus.REJECTED);
                rs.notes.put(o.taskId, o.note);
                rs.audit.append("TASK_REJECTED", o.taskId, "engine", Map.of("reason", o.note));
            }
            case CANCELLED -> {
                rs.status.put(o.taskId, TaskStatus.SKIPPED);
                rs.notes.put(o.taskId, "cancelled: " + o.note);
                rs.audit.append("TASK_CANCELLED", o.taskId, "engine", Map.of("reason", o.note));
            }
            case SAFE_STOP -> {
                rs.status.put(o.taskId, TaskStatus.FAILED);
                rs.notes.put(o.taskId, o.note);
                rs.audit.append("TASK_FAILED", o.taskId, "engine", Map.of("reason", o.note, "safeStop", true));
                rs.safeStop(o.note);
            }
        }
    }

    private void commit(RunState rs, TaskSpec spec, Outcome o) {
        List<String> produced = new ArrayList<>();
        Set<String> republished = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : o.output.artifacts().entrySet()) {
            ArtifactVersion av = rs.bb.put(e.getKey(), e.getValue(), "task:" + spec.id());
            produced.add(av.ref());
            if (av.version() > 1) {
                republished.add(e.getKey());
            }
            rs.audit.append("ARTIFACT_PUBLISHED", spec.id(), "agent:" + o.agent.name(),
                    Map.of("artifact", av.ref(), "hash", av.hash()));
        }
        List<String> consumed = new ArrayList<>();
        o.consumed.forEach((k, v) -> consumed.add(k + "@" + v));
        rs.lineage.record(new Lineage.Commit(spec.id(), o.attempt, o.agent.name(), consumed, produced,
                o.output.decisions()));
        rs.status.put(spec.id(), TaskStatus.SUCCEEDED);
        rs.notes.put(spec.id(), o.output.summary());
        rs.consumedAtCommit.put(spec.id(), o.consumed);
        rs.committed.put(spec.id(), o);
        rs.commitSeq.put(spec.id(), rs.seq.incrementAndGet());
        rs.completionOrder.add(spec.id());
        if (o.firstFailureMs >= 0) {
            long mttr = o.endMs - o.firstFailureMs;
            rs.metrics.recovered(mttr);
            rs.audit.append("INCIDENT_RECOVERED", spec.id(), "engine", Map.of("recoveryMillis", mttr));
        }
        rs.audit.append("TASK_SUCCEEDED", spec.id(), "agent:" + o.agent.name(),
                Map.of("attempt", (long) o.attempt, "summary", o.output.summary(), "decisions", o.output.decisions(),
                        "consumed", consumed, "produced", produced));
        if (!republished.isEmpty()) {
            invalidateStale(rs, republished, "artifact republished by " + spec.id());
        }
        try {
            rs.hook.onTaskSucceeded(spec.id(), rs);
        } catch (RuntimeException e) {
            rs.audit.append("HOOK_ERROR", spec.id(), "engine", Map.of("error", String.valueOf(e)));
        }
    }

    private void failTask(RunState rs, TaskSpec spec, Outcome o) {
        rs.status.put(spec.id(), TaskStatus.FAILED);
        rs.notes.put(spec.id(), o.note);
        rs.failures++;
        rs.audit.append("TASK_FAILED", spec.id(), "engine", Map.of("reason", o.note));
        if (spec.rollsBackAncestorsOnFailure()) {
            List<String> toRoll = new ArrayList<>();
            for (String a : rs.plan.ancestors(spec.id())) {
                if (rs.status.get(a) == TaskStatus.SUCCEEDED && rs.plan.get(a).mutating()) {
                    toRoll.add(a);
                }
            }
            toRoll.sort((x, y) -> Integer.compare(rs.commitSeq.get(y), rs.commitSeq.get(x)));
            for (String a : toRoll) {
                rollbackCommitted(rs, a, "downstream validation " + spec.id() + " failed");
                rs.status.put(a, TaskStatus.ROLLED_BACK);
                rs.notes.put(a, "rolled back after failure of " + spec.id());
                for (String d : rs.plan.descendants(a)) {
                    if (d.equals(spec.id())) {
                        continue;
                    }
                    TaskStatus ds = rs.status.get(d);
                    if (ds == TaskStatus.SUCCEEDED) {
                        rs.status.put(d, TaskStatus.INVALIDATED);
                        rs.notes.put(d, "based on rolled-back change by " + a);
                        rs.audit.append("TASK_INVALIDATED", d, "engine", Map.of("because", a));
                    } else if (ds == TaskStatus.RUNNING) {
                        rs.gen.merge(d, 1, Integer::sum); // in-flight result will be discarded on arrival
                        rs.audit.append("TASK_INVALIDATED", d, "engine", Map.of("because", a, "inFlight", true));
                    }
                }
            }
        }
        if (rs.failures >= cfg.maxTaskFailuresBeforeSafeStop && !rs.stopped.get()) {
            rs.safeStop(rs.failures + " task failures reached the circuit-breaker limit");
        }
    }

    private void rollbackCommitted(RunState rs, String taskId, String reason) {
        Outcome c = rs.committed.get(taskId);
        if (c == null || !c.agent.supportsRollback()) {
            return;
        }
        rs.audit.append("ROLLBACK_STARTED", taskId, "engine", Map.of("scope", "committed-task", "reason", reason));
        try {
            c.agent.rollback(c.ctx);
            rs.metrics.rollback();
            rs.audit.append("ROLLBACK_DONE", taskId, "agent:" + c.agent.name(), Map.of("scope", "committed-task"));
        } catch (Exception e) {
            rs.audit.append("ROLLBACK_FAILED", taskId, "agent:" + c.agent.name(), Map.of("error", String.valueOf(e)));
            rs.safeStop("rollback of " + taskId + " failed: " + e);
        }
    }

    private void discard(RunState rs, Outcome o, String why) {
        rs.audit.append("OUTCOME_DISCARDED", o.taskId, "engine", Map.of("reason", why, "kind", o.kind.name()));
        if (o.kind == Kind.SUCCESS && o.agent.supportsRollback() && rs.plan.get(o.taskId).mutating()) {
            try {
                o.agent.rollback(o.ctx);
                rs.metrics.rollback();
                rs.audit.append("ROLLBACK_DONE", o.taskId, "agent:" + o.agent.name(), Map.of("scope", "discarded"));
            } catch (Exception e) {
                rs.safeStop("rollback of discarded " + o.taskId + " failed: " + e);
            }
        }
    }

    /**
     * Dynamic re-planning: finds committed or running tasks whose inputs are now outdated, rolls back mutating ones in
     * reverse commit order, and re-queues them with everything downstream. Bounded by {@code maxReplans}.
     */
    private void invalidateStale(RunState rs, Set<String> changedKeys, String cause) {
        Set<String> stale = new LinkedHashSet<>();
        for (TaskSpec t : rs.plan.tasks()) {
            TaskStatus s = rs.status.get(t.id());
            if (s == TaskStatus.SUCCEEDED) {
                for (Map.Entry<String, Integer> e : rs.consumedAtCommit.getOrDefault(t.id(), Map.of()).entrySet()) {
                    if (rs.bb.latestVersion(e.getKey()) > e.getValue()) {
                        stale.add(t.id());
                        break;
                    }
                }
            } else if (s == TaskStatus.RUNNING && t.reads().stream().anyMatch(changedKeys::contains)) {
                stale.add(t.id());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        Set<String> reset = new LinkedHashSet<>(stale);
        stale.forEach(s -> reset.addAll(rs.plan.descendants(s)));
        List<String> rollbackOrder = new ArrayList<>();
        for (String id : reset) {
            if (rs.status.get(id) == TaskStatus.SUCCEEDED && rs.plan.get(id).mutating()) {
                rollbackOrder.add(id);
            }
        }
        rollbackOrder.sort((x, y) -> Integer.compare(rs.commitSeq.get(y), rs.commitSeq.get(x)));
        rollbackOrder.forEach(id -> rollbackCommitted(rs, id, cause));
        List<String> requeued = new ArrayList<>();
        for (String id : reset) {
            TaskStatus s = rs.status.get(id);
            if (s == TaskStatus.PENDING) {
                continue;
            }
            rs.gen.merge(id, 1, Integer::sum);
            rs.consumedAtCommit.remove(id);
            rs.committed.remove(id);
            rs.notes.remove(id);
            if (s != TaskStatus.RUNNING) {
                rs.status.put(id, TaskStatus.PENDING);
            }
            requeued.add(id);
        }
        rs.replans++;
        rs.metrics.replan();
        rs.audit.append("REPLAN", null, "engine", Map.of("cause", cause, "changed", new ArrayList<>(changedKeys),
                "requeued", requeued, "replanNumber", (long) rs.replans));
        if (rs.replans > cfg.maxReplans) {
            rs.safeStop("re-plan budget of " + cfg.maxReplans + " exceeded");
        }
    }

    private String validatePlan(RunState rs) {
        try {
            rs.plan.validate();
            for (TaskSpec t : rs.plan.tasks()) {
                List<Policy.Violation> vs = new ArrayList<>(Policy.evaluateAll(cfg.policies,
                        new Policy.Context(Policy.Phase.PLAN, t, agents.require(t.agent()), null)));
                if (t.fallbackAgent() != null) {
                    vs.addAll(Policy.evaluateAll(cfg.policies,
                            new Policy.Context(Policy.Phase.PLAN, t, agents.require(t.fallbackAgent()), null)));
                }
                report(rs, t.id(), vs);
                if (Policy.blocks(vs)) {
                    return vs.stream().filter(v -> v.severity().compareTo(Severity.WARN) > 0)
                            .map(Policy.Violation::message).findFirst().orElse("policy violation");
                }
            }
            return null;
        } catch (PlanException e) {
            return e.getMessage();
        }
    }

    private RunResult finish(RunState rs) {
        for (TaskSpec t : rs.plan.tasks()) {
            TaskStatus s = rs.status.get(t.id());
            if (s == TaskStatus.PENDING || s == TaskStatus.RUNNING) {
                rs.status.put(t.id(), TaskStatus.SKIPPED);
                String why = rs.stopReason != null ? rs.stopReason : "not reached";
                rs.notes.put(t.id(), "not run: " + why);
                rs.audit.append("TASK_SKIPPED", t.id(), "engine", Map.of("reason", why));
            }
        }
        RunStatus st;
        if (rs.stopReason != null && !rs.rejectedPlan) {
            st = RunStatus.SAFE_STOPPED;
        } else if (!rs.rejectedPlan && rs.status.values().stream().allMatch(s -> s == TaskStatus.SUCCEEDED)) {
            st = RunStatus.COMPLETED;
        } else {
            st = RunStatus.FAILED;
        }
        rs.metrics.runEnded(cfg.clock.millis());
        Map<String, TaskStatus> fin = new LinkedHashMap<>(rs.status);
        Map<String, Object> report = rs.metrics.report(fin);
        rs.audit.append("RUN_FINISHED", null, "engine", Map.of("status", st.name(),
                "stopReason", String.valueOf(rs.stopReason), "successRate", String.valueOf(report.get("successRate")),
                "auditEvents", (long) rs.audit.events().size()));
        return new RunResult(rs.runId, st, rs.stopReason, fin, new LinkedHashMap<>(rs.notes), rs.bb, rs.lineage,
                rs.audit, report, rs.plan, List.copyOf(rs.completionOrder));
    }

    private void report(RunState rs, String taskId, List<Policy.Violation> vs) {
        for (Policy.Violation v : vs) {
            rs.metrics.violation(v.category());
            rs.audit.append("POLICY_VIOLATION", taskId, "policy:" + v.policyId(),
                    Map.of("severity", v.severity().name(), "category", v.category().name(), "message", v.message()));
        }
    }

    // ---- worker ---------------------------------------------------------------------------------------------

    private Outcome work(RunState rs, TaskSpec spec, int gen) {
        long start = cfg.clock.millis();
        try {
            return doWork(rs, spec, gen, start);
        } catch (Throwable t) {
            rs.audit.append("ENGINE_ERROR", spec.id(), "engine", Map.of("error", String.valueOf(t)));
            return end(spec, gen, Kind.FAILED, null, null, null, 0, "engine error: " + t, start, -1);
        }
    }

    private Outcome end(TaskSpec spec, int gen, Kind kind, Agent agent, TaskContext ctx, AgentOutput out, int attempt,
                        String note, long start, long firstFailure) {
        return new Outcome(spec.id(), gen, kind, out, ctx == null ? Map.of() : ctx.consumed(), agent, ctx, attempt,
                note, start, cfg.clock.millis(), firstFailure);
    }

    private Outcome doWork(RunState rs, TaskSpec spec, int gen, long start) {
        String id = spec.id();
        rs.audit.append("TASK_STARTED", id, "engine", Map.of("stage", spec.stage().name(), "agent", spec.agent()));
        Agent primary = agents.require(spec.agent());
        Agent fallback = spec.fallbackAgent() == null ? null : agents.require(spec.fallbackAgent());

        GateResult entry = gate(rs, spec, spec.entryGate(), null, 0, "ENTRY_GATE");
        if (!entry.passed()) {
            return end(spec, gen, Kind.FAILED, null, null, null, 0,
                    "entry gate failed: " + String.join("; ", entry.findings()), start, -1);
        }
        List<Policy.Violation> pre = Policy.evaluateAll(cfg.policies,
                new Policy.Context(Policy.Phase.PRE_TASK, spec, primary, null));
        report(rs, id, pre);
        if (Policy.blocks(pre)) {
            return end(spec, gen, Policy.critical(pre) ? Kind.SAFE_STOP : Kind.FAILED, primary, null, null, 0,
                    "blocked by policy: " + pre.get(0).message(), start, -1);
        }
        Approval ap = spec.approval();
        if (ap != null && ap.timing() == Approval.Timing.BEFORE_EXECUTION) {
            Outcome rejected = approve(rs, spec, gen, start, null, null);
            if (rejected != null) {
                return rejected;
            }
        }
        int attemptNo = 0;
        long firstFailure = -1;
        String lastFailure = "no attempt executed";
        for (int phase = 0; phase < 2; phase++) {
            Agent agent = phase == 0 ? primary : fallback;
            int max = phase == 0 ? spec.maxAttempts() : spec.fallbackAttempts();
            if (agent == null || max == 0) {
                continue;
            }
            if (phase == 1) {
                rs.audit.append("FALLBACK_INVOKED", id, "engine",
                        Map.of("fallbackAgent", agent.name(), "afterFailure", lastFailure));
            }
            for (int i = 1; i <= max; i++) {
                if (rs.isCancelled(id, gen)) {
                    return end(spec, gen, Kind.CANCELLED, agent, null, null, attemptNo, "stop or re-plan requested",
                            start, firstFailure);
                }
                if (rs.metrics.totalAttempts() >= cfg.maxTotalAttempts) {
                    return end(spec, gen, Kind.SAFE_STOP, agent, null, null, attemptNo, "attempt budget exhausted",
                            start, firstFailure);
                }
                attemptNo++;
                if (attemptNo > 1) {
                    long delay = cfg.retryBaseMillis <= 0 ? 0
                            : Math.min(cfg.retryMaxMillis, cfg.retryBaseMillis << Math.min(20, attemptNo - 2));
                    rs.audit.append("RETRY_SCHEDULED", id, "engine", Map.of("attempt", (long) attemptNo,
                            "agent", agent.name(), "delayMillis", delay, "previousFailure", lastFailure));
                    try {
                        cfg.sleeper.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return end(spec, gen, Kind.CANCELLED, agent, null, null, attemptNo, "interrupted", start,
                                firstFailure);
                    }
                }
                TaskContext ctx = new TaskContext(spec, attemptNo, agent.name(), rs.bb, cfg.resources, rs.audit,
                        () -> rs.isCancelled(id, gen));
                rs.audit.append("ATTEMPT_STARTED", id, "agent:" + agent.name(),
                        Map.of("attempt", (long) attemptNo, "fallback", phase == 1));
                long t0 = cfg.clock.millis();
                String failure = null;
                boolean permanent = false;
                AgentOutput out = null;
                try {
                    out = agent.execute(ctx);
                    if (out == null) {
                        throw AgentException.permanent("agent returned no output");
                    }
                    for (String key : out.artifacts().keySet()) {
                        if (!spec.writes().contains(key)) {
                            throw new AutonomyViolationException("task " + id + " wrote undeclared artifact '" + key + "'");
                        }
                    }
                    List<Policy.Violation> post = Policy.evaluateAll(cfg.policies,
                            new Policy.Context(Policy.Phase.POST_TASK, spec, agent, out));
                    report(rs, id, post);
                    if (Policy.critical(post)) {
                        rs.metrics.attempt(id, false, attemptNo > 1, phase == 1);
                        rollbackAttempt(rs, spec, agent, ctx, "critical policy violation");
                        return end(spec, gen, Kind.SAFE_STOP, agent, ctx, null, attemptNo,
                                "critical policy violation: " + post.get(0).message(), start, firstFailure);
                    }
                    if (Policy.blocks(post)) {
                        failure = "blocked by policy: " + post.get(0).message();
                    }
                    if (failure == null) {
                        GateResult exit = gate(rs, spec, spec.exitGate(), out, attemptNo, "EXIT_GATE");
                        if (!exit.passed()) {
                            failure = "exit gate failed: " + String.join("; ", exit.findings());
                        }
                    }
                } catch (AutonomyViolationException e) {
                    failure = "autonomy violation: " + e.getMessage();
                    permanent = true;
                    rs.metrics.violation(PolicyCategory.AUTONOMY);
                    rs.audit.append("POLICY_VIOLATION", id, "engine", Map.of("severity", Severity.BLOCK.name(),
                            "category", PolicyCategory.AUTONOMY.name(), "message", e.getMessage()));
                } catch (AgentException e) {
                    failure = String.valueOf(e.getMessage());
                    permanent = !e.retryable();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return end(spec, gen, Kind.CANCELLED, agent, ctx, null, attemptNo, "interrupted", start,
                            firstFailure);
                } catch (Exception e) {
                    failure = "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                if (failure == null) {
                    rs.metrics.attempt(id, true, attemptNo > 1, phase == 1);
                    if (ap != null && ap.timing() == Approval.Timing.AFTER_EXECUTION) {
                        Outcome rejected = approve(rs, spec, gen, start, out, ctx);
                        if (rejected != null) {
                            rollbackAttempt(rs, spec, agent, ctx, "output rejected by human");
                            return rejected;
                        }
                    }
                    return end(spec, gen, Kind.SUCCESS, agent, ctx, out, attemptNo, "ok", start, firstFailure);
                }
                rs.metrics.attempt(id, false, attemptNo > 1, phase == 1);
                if (firstFailure < 0) {
                    firstFailure = t0;
                }
                lastFailure = failure;
                rs.audit.append("ATTEMPT_FAILED", id, "agent:" + agent.name(),
                        Map.of("attempt", (long) attemptNo, "reason", failure, "retryable", !permanent));
                if (!rollbackAttempt(rs, spec, agent, ctx, "attempt failed")) {
                    return end(spec, gen, Kind.SAFE_STOP, agent, ctx, null, attemptNo,
                            "rollback of failed attempt failed", start, firstFailure);
                }
                if (permanent) {
                    break;
                }
            }
        }
        return end(spec, gen, Kind.FAILED, null, null, null, 0,
                "exhausted " + attemptNo + " attempt(s); last failure: " + lastFailure, start, firstFailure);
    }

    private GateResult gate(RunState rs, TaskSpec spec, Gate gate, AgentOutput out, int attempt, String type) {
        GateResult r;
        try {
            r = gate.evaluate(new Gate.Input(spec, rs.bb, out, cfg.resources));
        } catch (Exception e) {
            r = GateResult.fail("gate-error", "gate threw " + e);
        }
        rs.audit.append(type, spec.id(), "gate:" + r.gate(),
                Map.of("passed", r.passed(), "findings", r.findings(), "attempt", (long) attempt));
        return r;
    }

    private boolean rollbackAttempt(RunState rs, TaskSpec spec, Agent agent, TaskContext ctx, String why) {
        if (!spec.mutating() || !agent.supportsRollback()) {
            return true;
        }
        rs.audit.append("ROLLBACK_STARTED", spec.id(), "engine", Map.of("scope", "attempt", "reason", why));
        try {
            agent.rollback(ctx);
            rs.metrics.rollback();
            rs.audit.append("ROLLBACK_DONE", spec.id(), "agent:" + agent.name(), Map.of("scope", "attempt"));
            return true;
        } catch (Exception e) {
            rs.audit.append("ROLLBACK_FAILED", spec.id(), "agent:" + agent.name(),
                    Map.of("scope", "attempt", "error", String.valueOf(e)));
            return false;
        }
    }

    /** Returns null if approved (or not required); otherwise a REJECTED outcome. */
    private Outcome approve(RunState rs, TaskSpec spec, int gen, long start, AgentOutput out, TaskContext ctx) {
        Approval ap = spec.approval();
        Optional<String> reason = ap.requiredReason(rs.bb);
        if (reason.isEmpty()) {
            return null;
        }
        // 'evidence' is what the human sees (versioned refs); 'fingerprint' decides whether an earlier approval still
        // applies, so it is keyed by artifact name + content hash and ignores version numbers.
        Map<String, String> evidence = new TreeMap<>();
        Map<String, String> fingerprint = new TreeMap<>();
        String summary = null;
        if (out == null) {
            for (String key : spec.reads()) {
                if (!key.startsWith("human.")) {
                    rs.bb.latest(key).ifPresent(v -> {
                        evidence.put(v.ref(), v.hash());
                        fingerprint.put(key, v.hash());
                    });
                }
            }
        } else {
            ctx.consumed().forEach((k, v) -> {
                if (!k.startsWith("human.")) {
                    rs.bb.version(k, v).ifPresent(av -> {
                        evidence.put(av.ref(), av.hash());
                        fingerprint.put(k, av.hash());
                    });
                }
            });
            out.artifacts().forEach((k, v) -> {
                String h = Hashing.sha256(Json.write(v));
                evidence.put("output:" + k, h);
                fingerprint.put("output:" + k, h);
            });
            summary = out.summary();
        }
        String fp = Hashing.sha256(Json.write(fingerprint));
        String memoKey = spec.id() + "|" + ap.timing();
        if (fp.equals(rs.approvedEvidence.get(memoKey))) {
            rs.metrics.approval("reused");
            rs.audit.append("APPROVAL_REUSED", spec.id(), "engine", Map.of("timing", ap.timing().name(), "fingerprint", fp));
            return null;
        }
        Approval.Request req = new Approval.Request(rs.runId + "-A" + rs.approvalCounter.incrementAndGet(), rs.runId,
                spec.id(), spec.stage(), ap.timing(), reason.get(), evidence, summary, ap.questions(rs.bb));
        rs.metrics.approval("requested");
        rs.audit.append("APPROVAL_REQUESTED", spec.id(), "engine", Map.of("requestId", req.id(),
                "timing", ap.timing().name(), "reason", req.reason(), "evidence", new LinkedHashMap<>(evidence),
                "questions", req.questions()));
        Approval.Decision d;
        try {
            d = approvals.decide(req);
            if (d == null) {
                d = Approval.Decision.reject("system", "no decision (fail closed)");
            }
        } catch (Exception e) {
            d = Approval.Decision.reject("system", "approval channel failure, failing closed: " + e);
        }
        rs.audit.append("APPROVAL_DECIDED", spec.id(), "human:" + d.approver(), Map.of("requestId", req.id(),
                "approved", d.approved(), "reason", String.valueOf(d.reason()),
                "inputKeys", new ArrayList<>(d.inputs().keySet())));
        if (!d.approved()) {
            rs.metrics.approval("rejected");
            return end(spec, gen, Kind.REJECTED, null, null, null, 0,
                    "rejected by " + d.approver() + ": " + d.reason(), start, -1);
        }
        rs.metrics.approval("granted");
        rs.approvedEvidence.put(memoKey, fp);
        if (!d.inputs().isEmpty()) {
            ArtifactVersion av = rs.bb.put("human." + spec.id(), d.inputs(), "human:" + d.approver());
            rs.audit.append("ARTIFACT_PUBLISHED", spec.id(), "human:" + d.approver(),
                    Map.of("artifact", av.ref(), "hash", av.hash()));
        }
        return null;
    }

    // ---- run state ------------------------------------------------------------------------------------------

    private final class RunState implements RunHook.Controls {
        final String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        final Plan plan;
        final Blackboard bb = new Blackboard(cfg.clock);
        final Lineage lineage = new Lineage();
        final AuditLog audit = new AuditLog(runId, cfg.clock, cfg.auditFile);
        final Metrics metrics = new Metrics();
        final Map<String, TaskStatus> status = new ConcurrentHashMap<>();
        final Map<String, String> notes = new LinkedHashMap<>();
        final Map<String, Integer> gen = new ConcurrentHashMap<>();
        final Map<String, Map<String, Integer>> consumedAtCommit = new LinkedHashMap<>();
        final Map<String, Outcome> committed = new LinkedHashMap<>();
        final Map<String, Integer> commitSeq = new LinkedHashMap<>();
        final List<String> completionOrder = new ArrayList<>();
        final Set<String> locksHeld = new LinkedHashSet<>();
        final ConcurrentLinkedQueue<Change> changes = new ConcurrentLinkedQueue<>();
        final Map<String, String> approvedEvidence = new ConcurrentHashMap<>();
        final AtomicInteger approvalCounter = new AtomicInteger();
        final AtomicInteger seq = new AtomicInteger();
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile String stopReason;
        final RunHook hook;
        boolean rejectedPlan;
        int failures;
        int replans;

        RunState(Plan plan, RunHook hook) {
            this.plan = plan;
            this.hook = hook;
        }

        boolean isCancelled(String taskId, int generation) {
            return stopped.get() || gen.get(taskId) != generation;
        }

        @Override
        public void safeStop(String reason) {
            if (stopped.compareAndSet(false, true)) {
                stopReason = reason;
                audit.append("SAFE_STOP", null, "engine", Map.of("reason", reason));
            }
        }

        @Override
        public void submitChange(String key, Object value, String author) {
            if (!plan.externalInputs().contains(key) && !key.startsWith("human.")) {
                throw new IllegalArgumentException("external changes may only target input or human.* artifacts, not '"
                        + key + "'");
            }
            changes.add(new Change(key, value, author));
        }
    }
}

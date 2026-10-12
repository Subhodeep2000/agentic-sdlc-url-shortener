package com.acme.orchestrator;

import static com.acme.orchestrator.Harness.none;
import static com.acme.orchestrator.Harness.ok;
import static com.acme.orchestrator.Harness.task;
import static com.acme.testkit.Assert.assertEquals;
import static com.acme.testkit.Assert.assertFalse;
import static com.acme.testkit.Assert.assertNotNull;
import static com.acme.testkit.Assert.assertThrows;
import static com.acme.testkit.Assert.assertTrue;

import com.acme.testkit.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Graph semantics, resilience, governance, re-planning and audit of the orchestration engine. */
public class EngineTest {

    // ---- plan validation -------------------------------------------------------------------------------------

    @Test
    public void planValidation() {
        Plan ok = new Plan().add(task("d", "x", "b", "c").build()).add(task("b", "x", "a").build())
                .add(task("c", "x", "a").build()).add(task("a", "x").build());
        ok.validate();
        List<String> order = ok.topologicalOrder();
        assertTrue(order.indexOf("a") < order.indexOf("b") && order.indexOf("c") < order.indexOf("d"));
        assertEquals(Set.of("b", "c", "d"), ok.descendants("a"));
        assertTrue(assertThrows(PlanException.class, () -> new Plan().add(task("a", "x", "b").build())
                .add(task("b", "x", "a").build()).validate()).getMessage().contains("cycle"));
        assertThrows(PlanException.class, () -> new Plan().add(task("a", "x", "ghost").build()).validate());
        assertTrue(assertThrows(PlanException.class, () -> new Plan().add(task("a", "x").writes("s").build())
                .add(task("b", "x").writes("s").build()).validate()).getMessage().contains("two producers"));
        assertTrue(assertThrows(PlanException.class, () -> new Plan().add(task("a", "x").build())
                .add(task("b", "x").reads("a.out").build()).validate()).getMessage().contains("unsynchronised"));
        assertThrows(PlanException.class, () -> task("a", "x").attempts(50, 0).build());
        assertEquals(List.of("a", "slow", "end"), new Plan().add(task("a", "x").build())
                .add(task("slow", "x", "a").build()).add(task("fast", "x", "a").build())
                .add(task("end", "x", "slow", "fast").build()).criticalPath(id -> id.equals("slow") ? 100 : 1));
    }

    // ---- execution flow --------------------------------------------------------------------------------------

    @Test
    public void diamondRunsBranchesInParallelAndJoins() {
        Harness h = new Harness();
        CyclicBarrier barrier = new CyclicBarrier(2); // passes only if both branches are in flight together
        h.agent("a", c -> ok("a"));
        h.agent("b", c -> {
            barrier.await(5, TimeUnit.SECONDS);
            return ok("b");
        });
        h.agent("c", c -> {
            barrier.await(5, TimeUnit.SECONDS);
            return ok("c");
        });
        h.agent("d", c -> AgentOutput.of("j").artifact("d.out", c.readString("b.out") + "+" + c.readString("c.out")));
        Plan plan = new Plan().add(task("a", "a").build()).add(task("b", "b", "a").build())
                .add(task("c", "c", "a").build()).add(task("d", "d", "b", "c").reads("b.out", "c.out").build());
        RunResult r = h.engine().run(plan, none(), null);
        assertEquals(RunStatus.COMPLETED, r.status(), r.taskNotes().toString());
        assertEquals("b+c", r.blackboard().latest("d.out").get().value());
        assertEquals("a", r.completionOrder().get(0));
        assertEquals("d", r.completionOrder().get(3));
    }

    @Test
    public void gatesBlockStartAndCommit() {
        Harness h = new Harness();
        Harness.Fn a = h.agent("a", c -> ok("a"));
        h.agent("b", c -> ok("b"));
        RunResult r = h.engine().run(new Plan().add(task("a", "a").entryGate(Gate.requireArtifacts("request")).build())
                .add(task("b", "b", "a").build()), none(), null);
        assertEquals(TaskStatus.FAILED, r.taskStatuses().get("a"));
        assertEquals(TaskStatus.SKIPPED, r.taskStatuses().get("b"));
        assertEquals(0, a.executions.get(), "agent must not run when the entry gate fails");

        Harness h2 = new Harness();
        AtomicInteger n = new AtomicInteger();
        h2.agent("a", c -> AgentOutput.of("x").artifact("a.out", n.incrementAndGet() == 1 ? "" : "value"));
        Gate nonEmpty = in -> ((String) in.output().artifacts().get("a.out")).isEmpty()
                ? GateResult.fail("non-empty", "a.out is empty") : GateResult.pass("non-empty");
        RunResult r2 = h2.engine().run(new Plan().add(task("a", "a").exitGate(nonEmpty).build()), none(), null);
        assertEquals(RunStatus.COMPLETED, r2.status());
        assertEquals("value", r2.blackboard().latest("a.out").get().value());
    }

    @Test
    public void locksSerialiseAndLineageTraces() {
        Harness h = new Harness();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        Plan plan = new Plan();
        for (String id : List.of("w1", "w2", "w3")) {
            h.agent(id, c -> {
                max.accumulateAndGet(active.incrementAndGet(), Math::max);
                Thread.sleep(30);
                active.decrementAndGet();
                return ok(id);
            });
            plan.add(task(id, id).locks("workspace").build());
        }
        assertEquals(RunStatus.COMPLETED, h.engine().run(plan, none(), null).status());
        assertEquals(1, max.get(), "lock must serialise tasks");

        Harness h2 = new Harness();
        h2.agent("a", c -> AgentOutput.of("a").artifact("a.out", "A:" + c.readString("request"))
                .decision("chose A because the request says so"));
        h2.agent("b", c -> AgentOutput.of("b").artifact("b.out", c.readString("a.out") + "!"));
        RunResult r = h2.engine().run(new Plan().externalInputs("request")
                .add(task("a", "a").reads("request").build()).add(task("b", "b", "a").reads("a.out").build()),
                Map.of("request", "build it"), null);
        List<String> trace = r.lineage().trace("b.out@1");
        assertTrue(trace.stream().anyMatch(s -> s.contains("a.out@1 <- a")), trace.toString());
        assertTrue(trace.stream().anyMatch(s -> s.contains("request@1 <- external input")), trace.toString());
        assertEquals("chose A because the request says so", r.lineage().commits().get(0).decisions().get(0));
    }

    @Test
    public void invalidPlanNeverRuns() {
        Harness h = new Harness();
        Harness.Fn a = h.agent("a", c -> ok("a"));
        RunResult r = h.engine().run(new Plan().add(task("a", "a", "b").build()).add(task("b", "a", "a").build()),
                none(), null);
        assertEquals(RunStatus.FAILED, r.status());
        assertTrue(r.stopReason().startsWith("plan rejected"), r.stopReason());
        assertEquals(0, a.executions.get());
    }

    // ---- resilience ------------------------------------------------------------------------------------------

    @Test
    public void retriesBackoffMttrAndBounds() {
        Harness h = new Harness();
        AtomicInteger n = new AtomicInteger();
        h.agent("flaky", c -> {
            if (n.incrementAndGet() < 3) {
                throw AgentException.transientFailure("boom");
            }
            return ok("t");
        });
        RunResult r = h.engine().run(new Plan().add(task("t", "flaky").attempts(3, 0).build()), none(), null);
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(List.of(100L, 200L), h.sleeps, "exponential backoff before attempts 2 and 3");
        assertEquals(2L, r.metricLong("retries"));
        assertEquals(1L, r.metricLong("incidentsRecovered"));
        assertFalse(Double.isNaN(r.metricDouble("mttrMillis")), "MTTR computed");

        Harness h2 = new Harness();
        Harness.Fn bad = h2.agent("bad", c -> {
            throw AgentException.transientFailure("always");
        });
        h2.agent("next", c -> ok("n"));
        RunResult r2 = h2.engine().run(new Plan().add(task("t", "bad").attempts(3, 0).build())
                .add(task("n", "next", "t").build()), none(), null);
        assertEquals(3, bad.executions.get(), "exactly maxAttempts executions");
        assertEquals(TaskStatus.SKIPPED, r2.taskStatuses().get("n"));
        assertEquals(0.0, r2.metricDouble("successRate"));
    }

    @Test
    public void fallbackAfterPermanentFailure() {
        Harness h = new Harness();
        Harness.Fn primary = h.agent("primary", c -> {
            throw AgentException.permanent("unsupported input");
        });
        Harness.Fn fb = h.agent("fb", c -> ok("t"));
        RunResult r = h.engine().run(new Plan().add(task("t", "primary").fallback("fb").attempts(3, 1).build()),
                none(), null);
        assertEquals(1, primary.executions.get(), "no retries after a permanent failure");
        assertEquals(1, fb.executions.get());
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(1L, r.metricLong("fallbacksUsed"));
    }

    @Test
    public void rollbackOfFailedAttemptAndOfAncestorsOnValidationFailure() {
        Harness h = new Harness();
        AtomicInteger n = new AtomicInteger();
        Harness.Fn m = h.mutating("mut", c -> {
            if (n.incrementAndGet() == 1) {
                throw AgentException.transientFailure("half-applied");
            }
            return ok("t");
        });
        RunResult r = h.engine().run(new Plan().add(task("t", "mut").capabilities(Capability.WRITE_WORKSPACE).build()),
                none(), null);
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(1, m.rollbacks.get(), "failed attempt's partial effects undone");

        Harness h2 = new Harness();
        Harness.Fn impl = h2.mutating("impl", c -> ok("impl"));
        h2.agent("docs", c -> ok("docs"));
        h2.agent("tests", c -> {
            throw AgentException.permanent("3 tests failed");
        });
        h2.agent("rel", c -> ok("release"));
        RunResult r2 = h2.engine().run(new Plan()
                .add(task("impl", "impl").capabilities(Capability.WRITE_WORKSPACE).build())
                .add(task("tests", "tests", "impl").attempts(1, 0).rollsBackAncestorsOnFailure(true).build())
                .add(task("docs", "docs", "impl").build())
                .add(task("release", "rel", "tests", "docs").build()), none(), null);
        assertEquals(TaskStatus.ROLLED_BACK, r2.taskStatuses().get("impl"));
        assertEquals(1, impl.rollbacks.get());
        assertEquals(TaskStatus.SKIPPED, r2.taskStatuses().get("release"));
        TaskStatus docs = r2.taskStatuses().get("docs");
        assertTrue(docs == TaskStatus.INVALIDATED || docs == TaskStatus.SKIPPED, "docs=" + docs);
        assertEquals(RunStatus.FAILED, r2.status());
    }

    @Test
    public void circuitBreakerBudgetAndDeadlineSafeStop() {
        Harness h = new Harness();
        h.cfg.parallelism(1).maxTaskFailuresBeforeSafeStop(3);
        Plan plan = new Plan();
        for (String id : List.of("f1", "f2", "f3", "f4")) {
            h.agent(id, c -> {
                throw AgentException.permanent("down");
            });
            plan.add(task(id, id).attempts(1, 0).build());
        }
        RunResult r = h.engine().run(plan, none(), null);
        assertEquals(RunStatus.SAFE_STOPPED, r.status());
        assertTrue(r.stopReason().contains("circuit-breaker"), r.stopReason());
        assertEquals(TaskStatus.SKIPPED, r.taskStatuses().get("f4"));

        Harness h2 = new Harness();
        h2.cfg.maxTotalAttempts(2).parallelism(1);
        h2.agent("bad", c -> {
            throw AgentException.transientFailure("x");
        });
        RunResult r2 = h2.engine().run(new Plan().add(task("t", "bad").attempts(5, 0).build()), none(), null);
        assertEquals(RunStatus.SAFE_STOPPED, r2.status());
        assertEquals(2L, r2.metricLong("attempts"));

        Harness h3 = new Harness();
        h3.cfg.deadline(java.time.Duration.ofMillis(100)).parallelism(1);
        h3.agent("slow", c -> {
            Thread.sleep(300);
            return ok("slow");
        });
        h3.agent("after", c -> ok("after"));
        RunResult r3 = h3.engine().run(new Plan().add(task("slow", "slow").build())
                .add(task("after", "after", "slow").build()), none(), null);
        assertEquals(RunStatus.SAFE_STOPPED, r3.status());
        assertTrue(r3.stopReason().contains("deadline"), r3.stopReason());
    }

    @Test
    public void killSwitchStopsRunWithoutStartingNewWork() throws Exception {
        Harness h = new Harness();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        h.agent("a", c -> {
            started.countDown();
            long end = System.currentTimeMillis() + 5000;
            while (!c.isCancelled() && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            return ok("a");
        });
        Harness.Fn b = h.agent("b", c -> ok("b"));
        Engine engine = h.engine();
        Thread operator = new Thread(() -> {
            try {
                started.await();
                engine.safeStop("operator pressed the kill switch");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        operator.start();
        RunResult r = engine.run(new Plan().add(task("a", "a").build()).add(task("b", "b", "a").build()), none(), null);
        operator.join();
        assertEquals(RunStatus.SAFE_STOPPED, r.status());
        assertEquals("operator pressed the kill switch", r.stopReason());
        assertEquals(0, b.executions.get(), "no new work after stop");
        assertTrue(r.audit().verify(), "audit chain intact after a stop");
    }

    // ---- governance ------------------------------------------------------------------------------------------

    @Test
    public void approvals() {
        Harness h = new Harness();
        h.approver = r -> Approval.Decision.reject("alice", "too risky");
        Harness.Fn a = h.agent("a", c -> ok("a"));
        h.agent("b", c -> ok("b"));
        RunResult r = h.engine().run(new Plan().add(task("a", "a")
                .approval(Approval.always(Approval.Timing.BEFORE_EXECUTION, "high impact")).build())
                .add(task("b", "b", "a").build()), none(), null);
        assertEquals(TaskStatus.REJECTED, r.taskStatuses().get("a"));
        assertEquals(TaskStatus.SKIPPED, r.taskStatuses().get("b"));
        assertEquals(0, a.executions.get(), "no action without approval");

        Harness broken = new Harness();
        broken.approver = x -> {
            throw new IllegalStateException("pager down");
        };
        Harness.Fn a2 = broken.agent("a", c -> ok("a"));
        RunResult r2 = broken.engine().run(new Plan().add(task("a", "a")
                .approval(Approval.always(Approval.Timing.BEFORE_EXECUTION, "x")).build()), none(), null);
        assertEquals(TaskStatus.REJECTED, r2.taskStatuses().get("a"), "fails closed");
        assertEquals(0, a2.executions.get());

        Harness input = new Harness();
        input.approver = x -> Approval.Decision.approve("bob", "answered", Map.of("retention_days", 90L));
        input.agent("a", c -> AgentOutput.of("a").artifact("a.out", "retention=" + c.readMap("human.a")
                .get("retention_days")));
        RunResult r3 = input.engine().run(new Plan().add(task("a", "a").reads("human.a")
                .approval(Approval.when(Approval.Timing.BEFORE_EXECUTION, bb -> java.util.Optional.of("needs input"),
                        bb -> List.of("How long to retain analytics?"))).build()), none(), null);
        assertEquals("retention=90", r3.blackboard().latest("a.out").get().value());
        assertEquals("human:bob", r3.blackboard().latest("human.a").get().producer());
        assertEquals("How long to retain analytics?", input.requests.get(0).questions().get(0));

        Harness after = new Harness();
        after.approver = x -> {
            assertTrue(x.evidence().containsKey("output:a.out"), "reviewer sees the output fingerprint");
            return Approval.Decision.reject("carol", "not ready");
        };
        Harness.Fn m = after.mutating("m", c -> ok("a"));
        RunResult r4 = after.engine().run(new Plan().add(task("a", "m").capabilities(Capability.WRITE_WORKSPACE)
                .approval(Approval.always(Approval.Timing.AFTER_EXECUTION, "sign-off")).build()), none(), null);
        assertEquals(TaskStatus.REJECTED, r4.taskStatuses().get("a"));
        assertFalse(r4.blackboard().has("a.out"), "rejected output is never committed");
        assertEquals(1, m.rollbacks.get());
    }

    @Test
    public void policiesAndAutonomyBoundaries() {
        Harness h = new Harness();
        Harness.Fn a = h.agent("nomut", c -> ok("a"));
        RunResult r = h.engine().run(new Plan().add(task("a", "nomut").capabilities(Capability.WRITE_WORKSPACE).build()),
                none(), null);
        assertTrue(r.stopReason().contains("cannot roll back"), r.stopReason());
        assertEquals(0, a.executions.get());
        assertEquals(1L, r.metricLong("policyViolations.change_control"));

        Harness rel = new Harness();
        rel.agent("a", c -> ok("a"));
        RunResult r2 = rel.engine().run(new Plan().add(TaskSpec.builder("rel", Stage.RELEASE_READINESS).agent("a")
                .writes("rel.out").build()), none(), null);
        assertTrue(r2.stopReason().contains("human approval"), r2.stopReason());

        Harness leak = new Harness();
        Harness.Fn m = leak.mutating("leaky", c -> AgentOutput.of("oops")
                .artifact("a.out", "config: password = \"hunter2hunter2\""));
        leak.agent("next", c -> ok("n"));
        RunResult r3 = leak.engine().run(new Plan().add(task("a", "leaky").capabilities(Capability.WRITE_WORKSPACE)
                .build()).add(task("n", "next", "a").build()), none(), null);
        assertEquals(RunStatus.SAFE_STOPPED, r3.status());
        assertFalse(r3.blackboard().has("a.out"), "secret-bearing output is never committed");
        assertEquals(1, m.rollbacks.get());
        assertEquals(1, m.executions.get(), "no blind retry after a critical violation");

        Harness snoop = new Harness();
        snoop.agent("producer", c -> AgentOutput.of("p").artifact("p.out", "secret-plan"));
        snoop.agent("snoop", c -> AgentOutput.of("s").artifact("s.out", c.readString("p.out")));
        snoop.agent("writer", c -> AgentOutput.of("w").artifact("w.out", "ok").artifact("sneaky", "x"));
        RunResult r4 = snoop.engine().run(new Plan().add(task("p", "producer").build())
                .add(task("s", "snoop", "p").attempts(2, 0).build())
                .add(task("w", "writer").attempts(1, 0).build()), none(), null);
        assertTrue(r4.taskNotes().get("s").contains("undeclared artifact"), r4.taskNotes().get("s"));
        assertEquals(TaskStatus.FAILED, r4.taskStatuses().get("w"));
        assertFalse(r4.blackboard().has("sneaky"));
        assertEquals(2L, r4.metricLong("policyViolations.autonomy"));
        assertEquals(3L, r4.metricLong("attempts"), "autonomy violations are not retried");
    }

    // ---- re-planning -----------------------------------------------------------------------------------------

    private Plan chain(Harness h, AtomicInteger analyzeRuns, boolean approvalOnImpl, boolean constant) {
        h.agent("analyze", c -> {
            analyzeRuns.incrementAndGet();
            String req = c.readString("request");
            return AgentOutput.of("analysed").artifact("analysis", constant ? "A" : "A:" + req);
        });
        h.mutating("implement", c -> AgentOutput.of("implemented").artifact("code", "code(" + c.readString("analysis") + ")"));
        h.agent("verify", c -> AgentOutput.of("verified").artifact("report", "verified " + c.readString("code")));
        TaskSpec.Builder impl = TaskSpec.builder("implement", Stage.IMPLEMENTATION).agent("implement")
                .dependsOn("analyze").reads("analysis").writes("code").capabilities(Capability.WRITE_WORKSPACE);
        if (approvalOnImpl) {
            impl.approval(Approval.always(Approval.Timing.BEFORE_EXECUTION, "change approval"));
        }
        return new Plan().externalInputs("request")
                .add(TaskSpec.builder("analyze", Stage.REQUIREMENTS).agent("analyze").reads("request")
                        .writes("analysis").build())
                .add(impl.build())
                .add(TaskSpec.builder("verify", Stage.TESTING).agent("verify").dependsOn("implement").reads("code")
                        .writes("report").build());
    }

    private static RunHook changeAfterVerify(String newValue, AtomicInteger fired) {
        return new RunHook() {
            @Override
            public void onTaskSucceeded(String taskId, Controls controls) {
                if (taskId.equals("verify") && fired.getAndIncrement() == 0) {
                    controls.submitChange("request", newValue, "product-owner");
                }
            }
        };
    }

    @Test
    public void upstreamChangeRollsBackAndReRunsDependentWork() {
        Harness h = new Harness();
        AtomicInteger analyzeRuns = new AtomicInteger();
        RunResult r = h.engine().run(chain(h, analyzeRuns, false, false), Map.of("request", "v1"),
                changeAfterVerify("v2", new AtomicInteger()));
        assertEquals(RunStatus.COMPLETED, r.status(), r.taskNotes().toString());
        assertEquals(2, analyzeRuns.get());
        assertEquals("verified code(A:v2)", r.blackboard().latest("report").get().value());
        assertEquals(1L, r.metricLong("replans"));
        assertEquals(1L, r.metricLong("rollbackActions"), "v1 mutating work was rolled back first");
        assertTrue(r.audit().events().stream().anyMatch(e -> e.type().equals("REPLAN")));
    }

    @Test
    public void approvalReusedWhenReplanLeavesEvidenceUnchanged() {
        Harness h = new Harness();
        RunResult r = h.engine().run(chain(h, new AtomicInteger(), true, true), Map.of("request", "original"),
                changeAfterVerify("cosmetic rewording", new AtomicInteger()));
        assertEquals(RunStatus.COMPLETED, r.status());
        assertEquals(1, h.requests.size(), "human asked only once");
        assertEquals(1L, r.metricLong("approvals.reused"));
    }

    @Test
    public void replanBudgetAndExternalChangeScope() {
        Harness h = new Harness();
        h.cfg.maxReplans(1);
        RunHook chaotic = new RunHook() {
            @Override
            public void onTaskSucceeded(String taskId, Controls controls) {
                if (taskId.equals("verify")) {
                    controls.submitChange("request", "change " + System.nanoTime(), "stakeholder");
                }
            }
        };
        RunResult r = h.engine().run(chain(h, new AtomicInteger(), false, false), Map.of("request", "v1"), chaotic);
        assertEquals(RunStatus.SAFE_STOPPED, r.status());
        assertTrue(r.stopReason().contains("re-plan budget"), r.stopReason());

        Harness h2 = new Harness();
        Throwable[] thrown = new Throwable[1];
        h2.engine().run(chain(h2, new AtomicInteger(), false, false), Map.of("request", "v1"), new RunHook() {
            @Override
            public void onTaskSucceeded(String taskId, Controls controls) {
                try {
                    controls.submitChange("code", "malicious", "mallory");
                } catch (IllegalArgumentException e) {
                    thrown[0] = e;
                }
            }
        });
        assertNotNull(thrown[0], "only inputs/human artifacts may be changed from outside the graph");
    }

    // ---- audit -----------------------------------------------------------------------------------------------

    @Test
    public void auditChainDetectsTamperingAndCoversDecisions() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("audit", ".jsonl");
        try {
            Harness h = new Harness();
            h.cfg.auditFile(file);
            h.agent("a", c -> ok("a"));
            RunResult r = h.engine().run(new Plan().add(task("a", "a").exitGate(Gate.outputHas("a.out"))
                    .approval(Approval.always(Approval.Timing.BEFORE_EXECUTION, "x")).build()), none(), null);
            assertTrue(r.audit().verify() && AuditLog.verifyFile(file), "intact log verifies");
            Set<String> types = new java.util.HashSet<>();
            r.audit().events().forEach(e -> types.add(e.type()));
            for (String t : List.of("RUN_STARTED", "PLAN_VALIDATED", "TASK_STARTED", "ENTRY_GATE", "APPROVAL_REQUESTED",
                    "APPROVAL_DECIDED", "ATTEMPT_STARTED", "EXIT_GATE", "ARTIFACT_PUBLISHED", "TASK_SUCCEEDED",
                    "RUN_FINISHED")) {
                assertTrue(types.contains(t), "missing audit event " + t);
            }
            String content = java.nio.file.Files.readString(file);
            java.nio.file.Files.writeString(file, content.replace("\"status\":\"COMPLETED\"", "\"status\":\"FAILED\""));
            assertFalse(AuditLog.verifyFile(file), "edited event breaks the chain");
            String[] lines = content.split("\n");
            java.nio.file.Files.writeString(file,
                    String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length)));
            assertFalse(AuditLog.verifyFile(file), "deleted event breaks the chain");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}

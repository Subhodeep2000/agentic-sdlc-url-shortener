package com.acme.orchestrator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared fixture: scripted agents, fake sleeper, programmable human approver. */
final class Harness {
    interface Body {
        AgentOutput run(TaskContext ctx) throws Exception;
    }

    static final class Fn implements Agent {
        final String name;
        final Body body;
        final boolean rollback;
        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();

        Fn(String name, boolean rollback, Body body) {
            this.name = name;
            this.rollback = rollback;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public AgentOutput execute(TaskContext ctx) throws Exception {
            executions.incrementAndGet();
            return body.run(ctx);
        }

        @Override
        public boolean supportsRollback() {
            return rollback;
        }

        @Override
        public void rollback(TaskContext ctx) {
            rollbacks.incrementAndGet();
        }
    }

    final AgentRegistry agents = new AgentRegistry();
    final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    final List<Approval.Request> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Approval.Handler approver = r -> Approval.Decision.approve("tester", "ok");
    final EngineConfig cfg = EngineConfig.defaults().sleeper(sleeps::add).retryBackoff(100, 1000)
            .deadline(Duration.ofSeconds(60));

    Fn agent(String name, Body body) {
        Fn a = new Fn(name, false, body);
        agents.register(a);
        return a;
    }

    Fn mutating(String name, Body body) {
        Fn a = new Fn(name, true, body);
        agents.register(a);
        return a;
    }

    Engine engine() {
        return new Engine(cfg, agents, r -> {
            requests.add(r);
            return approver.decide(r);
        });
    }

    static TaskSpec.Builder task(String id, String agent, String... deps) {
        return TaskSpec.builder(id, Stage.IMPLEMENTATION).agent(agent).dependsOn(deps).writes(id + ".out");
    }

    static AgentOutput ok(String id) {
        return AgentOutput.of(id + " ok").artifact(id + ".out", id);
    }

    static Map<String, Object> none() {
        return Map.of();
    }
}

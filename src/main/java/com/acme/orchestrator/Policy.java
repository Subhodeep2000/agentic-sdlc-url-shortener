package com.acme.orchestrator;

import java.util.ArrayList;
import java.util.List;

/** A guardrail evaluated by the engine at fixed lifecycle points; agents cannot skip or alter policies. */
public interface Policy {
    enum Phase { PLAN, PRE_TASK, POST_TASK }

    record Context(Phase phase, TaskSpec spec, Agent agent, AgentOutput output) {
    }

    record Violation(String policyId, PolicyCategory category, Severity severity, String message) {
    }

    String id();

    PolicyCategory category();

    List<Violation> evaluate(Context ctx);

    /** Evaluates all policies; a policy that throws is itself a BLOCK (fail closed). */
    static List<Violation> evaluateAll(List<Policy> policies, Context ctx) {
        List<Violation> all = new ArrayList<>();
        for (Policy p : policies) {
            try {
                all.addAll(p.evaluate(ctx));
            } catch (RuntimeException e) {
                all.add(new Violation(p.id(), p.category(), Severity.BLOCK, "policy evaluation failed: " + e));
            }
        }
        return all;
    }

    static boolean blocks(List<Violation> vs) {
        return vs.stream().anyMatch(v -> v.severity() == Severity.BLOCK || v.severity() == Severity.CRITICAL);
    }

    static boolean critical(List<Violation> vs) {
        return vs.stream().anyMatch(v -> v.severity() == Severity.CRITICAL);
    }
}

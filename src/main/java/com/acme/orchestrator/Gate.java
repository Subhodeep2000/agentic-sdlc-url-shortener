package com.acme.orchestrator;

import java.util.Map;

/** Entry/exit gate: a named, side-effect-free, engine-side check that must pass for a task to start or commit. */
@FunctionalInterface
public interface Gate {
    /** {@code output} is null for entry gates. Gates are trusted code and may read any artifact or resource. */
    record Input(TaskSpec spec, Blackboard blackboard, AgentOutput output, Map<String, Object> resources) {
        public <T> T resource(String name, Class<T> type) {
            Object r = resources.get(name);
            if (r == null) {
                throw new IllegalStateException("resource '" + name + "' not configured");
            }
            return type.cast(r);
        }
    }

    GateResult evaluate(Input in) throws Exception;

    Gate PASS = in -> GateResult.pass("none");

    static Gate requireArtifacts(String... keys) {
        return in -> {
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String k : keys) {
                if (!in.blackboard().has(k)) {
                    missing.add("missing artifact '" + k + "'");
                }
            }
            return missing.isEmpty() ? GateResult.pass("require-artifacts") : GateResult.fail("require-artifacts", missing);
        };
    }

    static Gate outputHas(String... keys) {
        return in -> {
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String k : keys) {
                if (in.output() == null || in.output().artifacts().get(k) == null) {
                    missing.add("output lacks artifact '" + k + "'");
                }
            }
            return missing.isEmpty() ? GateResult.pass("output-has") : GateResult.fail("output-has", missing);
        };
    }
}

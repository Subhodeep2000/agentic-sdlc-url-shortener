package com.acme.orchestrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Change-control: (1) any task that mutates the workspace must use an agent that can roll back (and so must its
 * fallback); (2) release-readiness tasks must carry a human approval checkpoint.
 */
public final class ChangeControlPolicy implements Policy {
    @Override
    public String id() {
        return "CC-1 change-control";
    }

    @Override
    public PolicyCategory category() {
        return PolicyCategory.CHANGE_CONTROL;
    }

    @Override
    public List<Violation> evaluate(Context ctx) {
        List<Violation> v = new ArrayList<>();
        if (ctx.phase() != Phase.PLAN) {
            return v;
        }
        if (ctx.spec().mutating() && ctx.agent() != null && !ctx.agent().supportsRollback()) {
            v.add(new Violation(id(), category(), Severity.BLOCK, "task " + ctx.spec().id()
                    + " mutates the workspace but agent '" + ctx.agent().name() + "' cannot roll back"));
        }
        if (ctx.spec().stage() == Stage.RELEASE_READINESS && ctx.spec().approval() == null) {
            v.add(new Violation(id(), category(), Severity.BLOCK,
                    "release task " + ctx.spec().id() + " requires a human approval checkpoint"));
        }
        return v;
    }
}

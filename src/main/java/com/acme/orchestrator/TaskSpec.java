package com.acme.orchestrator;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Declarative definition of one node of the workflow graph. Immutable. */
public final class TaskSpec {
    private final String id;
    private final Stage stage;
    private final String agent;
    private final String fallbackAgent;
    private final Set<String> dependsOn;
    private final Set<String> reads;
    private final Set<String> writes;
    private final Set<Capability> capabilities;
    private final Set<String> locks;
    private final int maxAttempts;
    private final int fallbackAttempts;
    private final Gate entryGate;
    private final Gate exitGate;
    private final Approval approval;
    private final boolean rollsBackAncestors;

    private TaskSpec(Builder b) {
        id = b.id;
        stage = b.stage;
        agent = b.agent;
        fallbackAgent = b.fallback;
        dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(b.dependsOn));
        reads = Collections.unmodifiableSet(new LinkedHashSet<>(b.reads));
        writes = Collections.unmodifiableSet(new LinkedHashSet<>(b.writes));
        capabilities = Collections.unmodifiableSet(b.caps.isEmpty() ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(b.caps));
        locks = Collections.unmodifiableSet(new LinkedHashSet<>(b.locks));
        maxAttempts = b.maxAttempts;
        fallbackAttempts = b.fallbackAttempts;
        entryGate = b.entry;
        exitGate = b.exit;
        approval = b.approval;
        rollsBackAncestors = b.rollsBack;
    }

    public static Builder builder(String id, Stage stage) {
        return new Builder(id, stage);
    }

    public String id() {
        return id;
    }

    public Stage stage() {
        return stage;
    }

    public String agent() {
        return agent;
    }

    public String fallbackAgent() {
        return fallbackAgent;
    }

    public Set<String> dependsOn() {
        return dependsOn;
    }

    public Set<String> reads() {
        return reads;
    }

    public Set<String> writes() {
        return writes;
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public Set<String> locks() {
        return locks;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int fallbackAttempts() {
        return fallbackAttempts;
    }

    public Gate entryGate() {
        return entryGate;
    }

    public Gate exitGate() {
        return exitGate;
    }

    public Approval approval() {
        return approval;
    }

    /** Mutating tasks change the workspace and therefore require rollback support. */
    public boolean mutating() {
        return capabilities.contains(Capability.WRITE_WORKSPACE);
    }

    /** Validation tasks: on permanent failure, mutating ancestors are rolled back. */
    public boolean rollsBackAncestorsOnFailure() {
        return rollsBackAncestors;
    }

    @Override
    public String toString() {
        return id;
    }

    public static final class Builder {
        private final String id;
        private final Stage stage;
        private String agent;
        private String fallback;
        private final Set<String> dependsOn = new LinkedHashSet<>();
        private final Set<String> reads = new LinkedHashSet<>();
        private final Set<String> writes = new LinkedHashSet<>();
        private final Set<Capability> caps = EnumSet.noneOf(Capability.class);
        private final Set<String> locks = new LinkedHashSet<>();
        private int maxAttempts = 2;
        private int fallbackAttempts = 1;
        private Gate entry = Gate.PASS;
        private Gate exit = Gate.PASS;
        private Approval approval;
        private boolean rollsBack;

        private Builder(String id, Stage stage) {
            if (id == null || !id.matches("[a-z0-9][a-z0-9._-]*")) {
                throw new PlanException("invalid task id '" + id + "'");
            }
            this.id = id;
            this.stage = stage;
        }

        public Builder agent(String a) {
            agent = a;
            return this;
        }

        public Builder fallback(String a) {
            fallback = a;
            return this;
        }

        public Builder dependsOn(String... ids) {
            dependsOn.addAll(Arrays.asList(ids));
            return this;
        }

        public Builder reads(String... k) {
            reads.addAll(Arrays.asList(k));
            return this;
        }

        public Builder writes(String... k) {
            writes.addAll(Arrays.asList(k));
            return this;
        }

        public Builder capabilities(Capability... c) {
            caps.addAll(Arrays.asList(c));
            return this;
        }

        public Builder locks(String... l) {
            locks.addAll(Arrays.asList(l));
            return this;
        }

        public Builder attempts(int primary, int fallbackN) {
            maxAttempts = primary;
            fallbackAttempts = fallbackN;
            return this;
        }

        public Builder entryGate(Gate g) {
            entry = g == null ? Gate.PASS : g;
            return this;
        }

        public Builder exitGate(Gate g) {
            exit = g == null ? Gate.PASS : g;
            return this;
        }

        public Builder approval(Approval a) {
            approval = a;
            return this;
        }

        public Builder rollsBackAncestorsOnFailure(boolean v) {
            rollsBack = v;
            return this;
        }

        public TaskSpec build() {
            if (agent == null) {
                throw new PlanException("task " + id + " has no agent");
            }
            if (maxAttempts < 1 || maxAttempts > 5 || fallbackAttempts < 0 || fallbackAttempts > 3) {
                throw new PlanException("task " + id + " has unbounded or invalid retry settings");
            }
            return new TaskSpec(this);
        }
    }
}

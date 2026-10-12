package com.acme.orchestrator;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Human approval checkpoint on a task. BEFORE_EXECUTION authorises a high-impact action (and may collect human input);
 * AFTER_EXECUTION lets a human review output before it is committed.
 */
public final class Approval {
    public enum Timing { BEFORE_EXECUTION, AFTER_EXECUTION }

    public record Request(String id, String runId, String taskId, Stage stage, Timing timing, String reason,
                          java.util.Map<String, String> evidence, String outputSummary, List<String> questions) {
    }

    /** A human's decision; {@code inputs} (answers/clarifications) become artifact {@code human.<taskId>}. */
    public record Decision(boolean approved, String approver, String reason, java.util.Map<String, Object> inputs) {
        public static Decision approve(String approver, String reason) {
            return new Decision(true, approver, reason, java.util.Map.of());
        }

        public static Decision approve(String approver, String reason, java.util.Map<String, Object> inputs) {
            return new Decision(true, approver, reason, inputs);
        }

        public static Decision reject(String approver, String reason) {
            return new Decision(false, approver, reason, java.util.Map.of());
        }
    }

    /** Channel to the human (console, ticketing, chat-ops...). Exceptions are treated as rejection (fail closed). */
    @FunctionalInterface
    public interface Handler {
        Decision decide(Request request) throws Exception;
    }

    private final Timing timing;
    private final Function<Blackboard, Optional<String>> reason;
    private final Function<Blackboard, List<String>> questions;

    private Approval(Timing t, Function<Blackboard, Optional<String>> r, Function<Blackboard, List<String>> q) {
        this.timing = t;
        this.reason = r;
        this.questions = q;
    }

    public static Approval always(Timing t, String reason) {
        return new Approval(t, bb -> Optional.of(reason), bb -> List.of());
    }

    public static Approval when(Timing t, Function<Blackboard, Optional<String>> reason,
                                Function<Blackboard, List<String>> questions) {
        return new Approval(t, reason, questions);
    }

    public Timing timing() {
        return timing;
    }

    public Optional<String> requiredReason(Blackboard bb) {
        return reason.apply(bb);
    }

    public List<String> questions(Blackboard bb) {
        return questions.apply(bb);
    }
}

package com.acme.orchestrator;

/**
 * A unit of autonomous work. Agents see only what their task declares and may only publish declared artifacts.
 * Agents that change external state must support rollback.
 */
public interface Agent {
    String name();

    AgentOutput execute(TaskContext ctx) throws Exception;

    default boolean supportsRollback() {
        return false;
    }

    /** Undo this agent's side effects for the task (idempotent). */
    default void rollback(TaskContext ctx) throws Exception {
    }
}

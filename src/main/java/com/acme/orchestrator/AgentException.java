package com.acme.orchestrator;

/** Failure raised by an agent. {@code retryable=false} skips the remaining retries of that agent. */
public class AgentException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    public AgentException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public static AgentException transientFailure(String m) {
        return new AgentException(m, true);
    }

    public static AgentException permanent(String m) {
        return new AgentException(m, false);
    }
}

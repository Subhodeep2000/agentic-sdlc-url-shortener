package com.acme.orchestrator;

/** An agent tried to read/write/use something outside its declared boundary. Never retried. */
public class AutonomyViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AutonomyViolationException(String message) {
        super(message);
    }
}

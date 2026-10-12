package com.acme.orchestrator;

public class PlanException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlanException(String message) {
        super(message);
    }
}

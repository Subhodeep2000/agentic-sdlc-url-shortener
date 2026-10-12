package com.acme.orchestrator;

/** Explicit autonomy boundary: what a task's agent may touch. Anything not granted is denied. */
public enum Capability { READ_WORKSPACE, WRITE_WORKSPACE }

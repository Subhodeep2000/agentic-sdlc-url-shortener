package com.acme.orchestrator;

/** INFO/WARN are recorded; BLOCK fails the attempt; CRITICAL fails it and safe-stops the whole run. */
public enum Severity { INFO, WARN, BLOCK, CRITICAL }

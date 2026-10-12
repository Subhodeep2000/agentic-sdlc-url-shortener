package com.acme.orchestrator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Run-wide governance limits and wiring. All limits are finite by construction. */
public final class EngineConfig {
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    int parallelism = 4;
    int maxTotalAttempts = 60;
    int maxReplans = 3;
    int maxTaskFailuresBeforeSafeStop = 3;
    Duration deadline = Duration.ofMinutes(10);
    long retryBaseMillis = 200;
    long retryMaxMillis = 5_000;
    Clock clock = Clock.systemUTC();
    Sleeper sleeper = Thread::sleep;
    Path auditFile;
    final Map<String, Object> resources = new LinkedHashMap<>();
    final List<Policy> policies = new ArrayList<>(List.of(new ChangeControlPolicy(), new SecretScanPolicy()));

    public static EngineConfig defaults() {
        return new EngineConfig();
    }

    public EngineConfig parallelism(int n) {
        parallelism = Math.max(1, Math.min(n, 16));
        return this;
    }

    public EngineConfig maxTotalAttempts(int n) {
        maxTotalAttempts = n;
        return this;
    }

    public EngineConfig maxReplans(int n) {
        maxReplans = n;
        return this;
    }

    public EngineConfig maxTaskFailuresBeforeSafeStop(int n) {
        maxTaskFailuresBeforeSafeStop = n;
        return this;
    }

    public EngineConfig deadline(Duration d) {
        deadline = d;
        return this;
    }

    public EngineConfig retryBackoff(long baseMillis, long maxMillis) {
        retryBaseMillis = baseMillis;
        retryMaxMillis = maxMillis;
        return this;
    }

    public EngineConfig clock(Clock c) {
        clock = c;
        return this;
    }

    public EngineConfig sleeper(Sleeper s) {
        sleeper = s;
        return this;
    }

    public EngineConfig auditFile(Path p) {
        auditFile = p;
        return this;
    }

    public EngineConfig resource(String name, Object value) {
        resources.put(name, value);
        return this;
    }

    public EngineConfig policy(Policy p) {
        policies.add(p);
        return this;
    }
}

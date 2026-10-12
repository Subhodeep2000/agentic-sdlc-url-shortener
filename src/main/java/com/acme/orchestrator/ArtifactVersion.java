package com.acme.orchestrator;

import java.time.Instant;

/** One immutable version of a named artifact, with provenance and content hash. */
public record ArtifactVersion(String key, int version, Object value, String producer, Instant at, String hash) {
    public String ref() {
        return key + "@" + version;
    }
}

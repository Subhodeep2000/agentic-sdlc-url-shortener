package com.acme.orchestrator;

import com.acme.common.Hashing;
import com.acme.common.Json;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned, append-only artifact store: the shared cross-stage context. Values are deep-copied through JSON and
 * frozen, so an agent cannot mutate what another agent has read.
 */
public final class Blackboard {
    private final Map<String, List<ArtifactVersion>> history = new LinkedHashMap<>();
    private final Clock clock;

    public Blackboard(Clock clock) {
        this.clock = clock;
    }

    public synchronized ArtifactVersion put(String key, Object value, String producer) {
        String json = Json.write(value);
        List<ArtifactVersion> v = history.computeIfAbsent(key, k -> new ArrayList<>());
        ArtifactVersion av = new ArtifactVersion(key, v.size() + 1, freeze(Json.parse(json)), producer,
                clock.instant(), Hashing.sha256(json));
        v.add(av);
        return av;
    }

    public synchronized Optional<ArtifactVersion> latest(String key) {
        List<ArtifactVersion> v = history.get(key);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(v.size() - 1));
    }

    public synchronized int latestVersion(String key) {
        List<ArtifactVersion> v = history.get(key);
        return v == null ? 0 : v.size();
    }

    public synchronized Optional<ArtifactVersion> version(String key, int n) {
        List<ArtifactVersion> v = history.get(key);
        return v == null || n < 1 || n > v.size() ? Optional.empty() : Optional.of(v.get(n - 1));
    }

    public boolean has(String key) {
        return latest(key).isPresent();
    }

    public synchronized List<String> keys() {
        return List.copyOf(history.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Object freeze(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> c = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> c.put(k, freeze(v)));
            return Collections.unmodifiableMap(c);
        }
        if (o instanceof List<?> l) {
            List<Object> c = new ArrayList<>();
            l.forEach(e -> c.add(freeze(e)));
            return Collections.unmodifiableList(c);
        }
        return o;
    }
}

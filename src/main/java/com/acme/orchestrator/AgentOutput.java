package com.acme.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What an agent hands back: artifacts, a summary, and the decisions it took (for lineage). */
public final class AgentOutput {
    private final Map<String, Object> artifacts = new LinkedHashMap<>();
    private final List<String> decisions = new ArrayList<>();
    private final String summary;

    private AgentOutput(String summary) {
        this.summary = Objects.requireNonNull(summary, "summary");
    }

    public static AgentOutput of(String summary) {
        return new AgentOutput(summary);
    }

    public AgentOutput artifact(String key, Object value) {
        artifacts.put(key, value);
        return this;
    }

    public AgentOutput decision(String text) {
        decisions.add(text);
        return this;
    }

    public Map<String, Object> artifacts() {
        return artifacts;
    }

    public List<String> decisions() {
        return decisions;
    }

    public String summary() {
        return summary;
    }
}

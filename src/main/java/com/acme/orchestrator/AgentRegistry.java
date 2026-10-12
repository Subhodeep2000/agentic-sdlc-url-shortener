package com.acme.orchestrator;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentRegistry {
    private final Map<String, Agent> agents = new LinkedHashMap<>();

    public AgentRegistry register(Agent a) {
        if (agents.putIfAbsent(a.name(), a) != null) {
            throw new IllegalArgumentException("duplicate agent " + a.name());
        }
        return this;
    }

    public Agent require(String name) {
        Agent a = agents.get(name);
        if (a == null) {
            throw new PlanException("unknown agent '" + name + "'");
        }
        return a;
    }
}

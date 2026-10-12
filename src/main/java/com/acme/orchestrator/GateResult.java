package com.acme.orchestrator;

import java.util.List;

public record GateResult(String gate, boolean passed, List<String> findings) {
    public static GateResult pass(String gate) {
        return new GateResult(gate, true, List.of());
    }

    public static GateResult fail(String gate, String... findings) {
        return new GateResult(gate, false, List.of(findings));
    }

    public static GateResult fail(String gate, List<String> findings) {
        return new GateResult(gate, false, List.copyOf(findings));
    }
}

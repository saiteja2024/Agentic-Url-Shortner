package io.agentic.orchestrator.gate;

import org.springframework.lang.Nullable;

public record GateResult(Outcome outcome, @Nullable String reason) {

    public enum Outcome {
        PASS,
        FAIL,
        NEEDS_APPROVAL
    }

    public static GateResult pass() {
        return new GateResult(Outcome.PASS, null);
    }

    public static GateResult pass(String reason) {
        return new GateResult(Outcome.PASS, reason);
    }

    public static GateResult fail(String reason) {
        return new GateResult(Outcome.FAIL, reason);
    }

    public static GateResult needsApproval(String reason) {
        return new GateResult(Outcome.NEEDS_APPROVAL, reason);
    }
}

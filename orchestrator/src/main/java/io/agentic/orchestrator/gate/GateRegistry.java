package io.agentic.orchestrator.gate;

import io.agentic.orchestrator.pipeline.GateSpec;

/** Resolves a declared gate to its implementation, by gate id and type. */
@FunctionalInterface
public interface GateRegistry {

    Gate resolve(String gateId, GateSpec spec);

    /** Only the built-in approval gate; everything else must be registered explicitly. */
    static GateRegistry defaults() {
        return (id, spec) -> {
            if (spec.isApproval()) {
                return ctx -> GateResult.needsApproval(
                        spec.description() != null ? spec.description() : "human approval required");
            }
            throw new IllegalArgumentException("no implementation for gate '" + id + "' of type '" + spec.type() + "'");
        };
    }
}

package io.agentic.orchestrator.gate;

/** A check before (entry) or after (exit) a node (ORCH-008). */
@FunctionalInterface
public interface Gate {

    GateResult evaluate(GateContext context) throws Exception;
}

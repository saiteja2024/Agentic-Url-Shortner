package io.agentic.orchestrator.dag;

/** Kinds of audit events (ORCH-012). */
public enum EventType {
    RUN_STARTED,
    RUN_FINISHED,
    NODE_TRANSITION,
    NODE_SKIPPED,
    GATE_EVALUATED,
    APPROVAL_DECIDED,
    ARTIFACT_RECORDED
}

package io.agentic.orchestrator.dag;

public enum RunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** Stopped by the kill switch, a budget, or shutdown (ORCH-035). */
    CANCELLED
}

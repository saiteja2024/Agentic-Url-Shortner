package io.agentic.orchestrator.dag;

import java.util.EnumSet;
import java.util.Set;

/** Node lifecycle (ORCH-006). */
public enum NodeStatus {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    DONE,
    FAILED,
    STALE;

    /** Whether {@code this → next} is one of the specified transitions. */
    public boolean canTransitionTo(NodeStatus next) {
        return allowedNext().contains(next);
    }

    private Set<NodeStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(RUNNING, DONE); // DONE only for a skipped conditional stage
            case RUNNING -> EnumSet.of(DONE, FAILED, WAITING_APPROVAL);
            case WAITING_APPROVAL -> EnumSet.of(DONE, FAILED, RUNNING); // RUNNING: re-execution after clarification
            case DONE -> EnumSet.of(STALE);
            case STALE -> EnumSet.of(RUNNING);
            case FAILED -> EnumSet.noneOf(NodeStatus.class);
        };
    }
}

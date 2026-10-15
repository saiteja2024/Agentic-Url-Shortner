package io.agentic.orchestrator.dag;

import org.springframework.lang.Nullable;

/** Mutable, thread-safe state of one node within one run. */
public final class Node {

    private final String id;
    private NodeStatus status = NodeStatus.PENDING;
    private int attempt;
    private String failureReason;

    public Node(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public synchronized NodeStatus status() {
        return status;
    }

    public synchronized int attempt() {
        return attempt;
    }

    @Nullable
    public synchronized String failureReason() {
        return failureReason;
    }

    /**
     * Moves to {@code next}, returning the previous status. Entering RUNNING starts a new attempt.
     *
     * @throws IllegalNodeTransitionException if the transition is not allowed; the status is unchanged
     */
    public synchronized NodeStatus transitionTo(NodeStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalNodeTransitionException(id, status, next);
        }
        NodeStatus previous = status;
        status = next;
        if (next == NodeStatus.RUNNING) {
            attempt++;
            failureReason = null;
        }
        return previous;
    }

    /** Starts another attempt without leaving RUNNING (a retry); returns the new attempt number. */
    public synchronized int nextAttempt() {
        if (status != NodeStatus.RUNNING) {
            throw new IllegalNodeTransitionException(id, status, NodeStatus.RUNNING);
        }
        failureReason = null;
        return ++attempt;
    }

    synchronized void recordFailure(String reason) {
        this.failureReason = reason;
    }
}

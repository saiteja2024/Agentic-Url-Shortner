package io.agentic.orchestrator.dag;

/** A transition outside the ORCH-006 state machine: a programming error, never a user error. */
public class IllegalNodeTransitionException extends IllegalStateException {

    public IllegalNodeTransitionException(String nodeId, NodeStatus from, NodeStatus to) {
        super("node '" + nodeId + "': illegal transition " + from + " -> " + to);
    }
}

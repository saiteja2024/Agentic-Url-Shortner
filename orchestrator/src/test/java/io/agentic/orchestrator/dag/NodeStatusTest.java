package io.agentic.orchestrator.dag;

import static io.agentic.orchestrator.dag.NodeStatus.DONE;
import static io.agentic.orchestrator.dag.NodeStatus.FAILED;
import static io.agentic.orchestrator.dag.NodeStatus.PENDING;
import static io.agentic.orchestrator.dag.NodeStatus.RUNNING;
import static io.agentic.orchestrator.dag.NodeStatus.STALE;
import static io.agentic.orchestrator.dag.NodeStatus.WAITING_APPROVAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class NodeStatusTest {

    private static final Map<NodeStatus, Set<NodeStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(RUNNING, DONE),
            RUNNING, EnumSet.of(DONE, FAILED, WAITING_APPROVAL),
            WAITING_APPROVAL, EnumSet.of(DONE, FAILED),
            DONE, EnumSet.of(STALE),
            STALE, EnumSet.of(RUNNING),
            FAILED, EnumSet.noneOf(NodeStatus.class));

    @Test
    @Tag("ORCH-006")
    @DisplayName("ORCH-006: exactly the specified transitions are allowed")
    void transitionTable() {
        for (NodeStatus from : NodeStatus.values()) {
            for (NodeStatus to : NodeStatus.values()) {
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @Test
    @Tag("ORCH-006")
    @DisplayName("ORCH-006: an illegal transition is rejected and leaves the status unchanged")
    void illegalTransitionRejected() {
        Node node = new Node("design");
        node.transitionTo(RUNNING);

        assertThatThrownBy(() -> node.transitionTo(PENDING))
                .isInstanceOf(IllegalNodeTransitionException.class)
                .hasMessageContaining("design")
                .hasMessageContaining("RUNNING -> PENDING");
        assertThat(node.status()).isEqualTo(RUNNING);
    }
}

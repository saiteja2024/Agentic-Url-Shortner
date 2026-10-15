package io.agentic.orchestrator.dag;

import static io.agentic.orchestrator.support.Stages.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.orchestrator.pipeline.PipelineDefinitionException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class DagPlanTest {

    @Test
    @Tag("ORCH-004")
    @DisplayName("ORCH-004: topological order respects dependencies and declaration order")
    void kahnOrderIsDeterministic() {
        DagPlan plan = DagPlan.of(List.of(
                stage("docs", "test"),
                stage("design", "requirements"),
                stage("requirements"),
                stage("test-plan", "design"),
                stage("implement", "design"),
                stage("test", "implement", "test-plan")));

        // Ready nodes are taken in declaration order: test-plan is declared before implement.
        assertThat(plan.topologicalOrder())
                .containsExactly("requirements", "design", "test-plan", "implement", "test", "docs");
        assertThat(plan.dependents("design")).containsExactly("test-plan", "implement");
    }

    @Test
    @Tag("ORCH-003")
    @DisplayName("ORCH-003: detects a self-loop and a longer cycle, naming the path")
    void detectsCycles() {
        assertThatThrownBy(() -> DagPlan.of(List.of(stage("a", "a"))))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageContaining("a → a");
        assertThatThrownBy(() -> DagPlan.of(List.of(stage("x"), stage("a", "c"), stage("b", "a"), stage("c", "b"))))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageMatching("(?s).*dependency cycle: (a → c → b → a|b → a → c → b|c → b → a → c).*");
    }
}

package io.agentic.orchestrator.pipeline;

import static io.agentic.orchestrator.support.Stages.gate;
import static io.agentic.orchestrator.support.Stages.pipeline;
import static io.agentic.orchestrator.support.Stages.stage;
import static io.agentic.orchestrator.support.Stages.withGates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PipelineLoaderTest {

    private static final String SDLC_PIPELINE = "/pipelines/pipeline.yaml";

    @Test
    @Tag("ORCH-001")
    @DisplayName("ORCH-001: loads the real SDLC pipeline from specs/orchestrator/pipeline.yaml")
    void loadsRealPipeline() throws Exception {
        PipelineDefinition def;
        try (InputStream in = getClass().getResourceAsStream(SDLC_PIPELINE)) {
            def = PipelineLoader.load(in);
        }

        assertThat(def.metadata().name()).isEqualTo("sdlc-default");
        assertThat(def.stages())
                .extracting(StageSpec::id)
                .containsExactly(
                        "requirements",
                        "impact-analysis",
                        "design",
                        "implement",
                        "test-plan",
                        "test",
                        "docs",
                        "release");
        assertThat(def.stage("design").dependsOn()).containsExactly("requirements", "impact-analysis");
        assertThat(def.stage("test").dependsOn()).containsExactly("implement", "test-plan");
        assertThat(def.stage("impact-analysis").when()).isEqualTo("brownfield");
        assertThat(def.gates().get("human-approval").type()).isEqualTo("approval");
        assertThat(def.approvalPolicy()).extracting(ApprovalRule::id).contains("schema-change", "merge");
    }

    @Test
    @Tag("ORCH-001")
    @DisplayName("ORCH-001: unknown keys fail loading and are named")
    void rejectsUnknownKeys() {
        assertThatThrownBy(
                        () -> load(
                                """
                        apiVersion: agentic.io/v1alpha1
                        kind: Pipeline
                        metadata: {name: x, version: "1"}
                        stages:
                          - {id: a, agent: A, dependOn: []}
                        """))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageContaining("dependOn");
    }

    @Test
    @Tag("ORCH-001")
    @DisplayName("ORCH-001: missing id or agent and malformed YAML fail with a clear message")
    void rejectsMissingFieldsAndBadYaml() {
        assertThatThrownBy(
                        () -> load(
                                """
                        apiVersion: agentic.io/v1alpha1
                        kind: Pipeline
                        metadata: {name: x, version: "1"}
                        stages:
                          - {agent: A}
                          - {id: b}
                        """))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageContaining("stage #1 has no id")
                .hasMessageContaining("stage 'b' has no agent");
        assertThatThrownBy(() -> load("stages: [ {id: a"))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageContaining("not valid pipeline YAML");
        assertThatThrownBy(() -> PipelineLoader.load(null))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Tag("ORCH-002")
    @DisplayName("ORCH-002: reports every graph problem at once")
    void reportsAllProblems() {
        PipelineDefinition def = pipeline(
                Map.of("approve", gate("approval"), "lint", gate("command")),
                stage("a"),
                stage("a"),
                stage("b", "ghost"),
                withGates(stage("c", "a"), List.of("approve"), List.of("missing-gate")));

        assertThatThrownBy(() -> PipelineValidator.validate(def))
                .isInstanceOfSatisfying(PipelineDefinitionException.class, e -> assertThat(e.problems())
                        .containsExactlyInAnyOrder(
                                "duplicate stage id 'a'",
                                "stage 'b' depends on unknown stage 'ghost'",
                                "stage 'c' uses approval gate 'approve' as an entry gate; approvals are exit-only",
                                "stage 'c' references undeclared gate 'missing-gate'"));
    }

    @Test
    @Tag("ORCH-003")
    @DisplayName("ORCH-003: a dependency cycle fails loading and names its stages")
    void rejectsCycles() {
        PipelineDefinition def = pipeline(
                stage("requirements"), stage("design", "requirements", "implement"), stage("implement", "design"));

        assertThatThrownBy(() -> PipelineValidator.validate(def))
                .isInstanceOf(PipelineDefinitionException.class)
                .hasMessageContaining("dependency cycle")
                .hasMessageContaining("design")
                .hasMessageContaining("implement")
                .hasMessageNotContaining("requirements →");
    }

    private static PipelineDefinition load(String yaml) {
        return PipelineLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}

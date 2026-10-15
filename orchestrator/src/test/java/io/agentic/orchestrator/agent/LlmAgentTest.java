package io.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.orchestrator.dag.Artifact;
import io.agentic.orchestrator.dag.NodeContext;
import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.dag.Question;
import io.agentic.orchestrator.dag.RunMode;
import io.agentic.orchestrator.pipeline.StageSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmAgentTest {

    @TempDir
    Path workspace;

    private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

    private LlmClient replying(String text) {
        return request -> {
            lastRequest.set(request);
            return new LlmResponse(text, 120, 80, "test-model");
        };
    }

    private NodeContext context(
            String nodeId, Map<String, List<Artifact>> upstream, Map<String, String> answers, String feedback) {
        StageSpec stage =
                new StageSpec(nodeId, "X", null, null, List.copyOf(upstream.keySet()), null, null, null, null, null);
        return new NodeContext(
                UUID.randomUUID(),
                nodeId,
                stage,
                RunMode.GREENFIELD,
                "greenfield",
                "Add analytics",
                2,
                upstream,
                answers,
                feedback,
                workspace);
    }

    @Test
    @Tag("ORCH-021")
    @DisplayName("ORCH-021: the prompt carries the request, upstream artifacts, answers and the last failure")
    void promptCarriesContext() throws Exception {
        Artifact design = new Artifact(UUID.randomUUID(), "design", "design.md", "Use LongAdder", "h", List.of());
        LlmAgent agent = AgentCatalog.create("ImplementAgent", replying("done"));

        agent.execute(context(
                "implement", Map.of("design", List.of(design)), Map.of("Q1", "yes"), "exit gate 'compile' failed: x"));

        LlmRequest request = lastRequest.get();
        assertThat(request.agent()).isEqualTo("ImplementAgent");
        assertThat(request.attempt()).isEqualTo(2);
        assertThat(request.system()).contains("implementation engineer");
        assertThat(request.prompt())
                .contains("Add analytics")
                .contains("design/design.md")
                .contains("Use LongAdder")
                .contains("Q1: yes")
                .contains("exit gate 'compile' failed: x");
    }

    @Test
    @Tag("ORCH-021")
    @DisplayName("ORCH-021: file blocks are written into the workspace and summarised as the artifact")
    void writesFiles() throws Exception {
        LlmAgent agent = AgentCatalog.create(
                "ImplementAgent",
                replying(
                        """
                Implementing.

                <<<FILE src/main/java/A.java
                class A {}
                >>>
                """));

        NodeOutput output = agent.execute(context("implement", Map.of(), Map.of(), null));

        assertThat(Files.readString(workspace.resolve("src/main/java/A.java"))).isEqualTo("class A {}\n");
        assertThat(output.artifacts().get("implement.md"))
                .contains("Implementing.")
                .contains("src/main/java/A.java")
                .doesNotContain("class A {}");
        assertThat(output.inputTokens()).isEqualTo(120);
        assertThat(output.outputTokens()).isEqualTo(80);
    }

    @Test
    @Tag("ORCH-021")
    @DisplayName("ORCH-021: an agent can never write outside its workspace")
    void rejectsPathTraversal() {
        LlmAgent agent = AgentCatalog.create("ImplementAgent", replying("<<<FILE ../escape.txt\nx\n>>>\n"));
        assertThatThrownBy(() -> agent.execute(context("implement", Map.of(), Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the workspace");
        LlmAgent absolute = AgentCatalog.create("ImplementAgent", replying("<<<FILE /tmp/escape.txt\nx\n>>>\n"));
        assertThatThrownBy(() -> absolute.execute(context("implement", Map.of(), Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("ORCH-034")
    @DisplayName("ORCH-034: the requirements agent surfaces open questions")
    void parsesQuestions() throws Exception {
        LlmAgent agent = AgentCatalog.create(
                "RequirementsAgent",
                replying(
                        """
                Draft.

                ## Open questions
                - Q1: Vanity aliases?
                - Q2: Expiry?
                """));

        NodeOutput output = agent.execute(context("requirements", Map.of(), Map.of(), null));

        assertThat(output.questions())
                .containsExactly(new Question("Q1", "Vanity aliases?"), new Question("Q2", "Expiry?"));
        assertThat(output.artifacts()).containsKey("requirements.md");
    }

    @Test
    @Tag("ORCH-031")
    @DisplayName("ORCH-031: the release agent's merge request becomes an action")
    void parsesActions() throws Exception {
        LlmAgent agent = AgentCatalog.create("ReleaseAgent", replying("Notes\n\nACTION: merge\n"));
        assertThat(agent.execute(context("release", Map.of(), Map.of(), null)).actions())
                .containsExactly("merge");
    }

    @Test
    @Tag("ORCH-021")
    @DisplayName("ORCH-021: impact analysis fails if it names a file that does not exist")
    void impactAnalysisChecksFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Real.java"), "class Real {}");
        String reply = "## Impacted files\n- src/Real.java\n- src/Imaginary.java\n";
        LlmAgent agent = AgentCatalog.create("ImpactAnalysisAgent", replying(reply));

        assertThatThrownBy(() -> agent.execute(context("impact-analysis", Map.of(), Map.of(), null)))
                .hasMessageContaining("src/Imaginary.java");

        LlmAgent ok = AgentCatalog.create("ImpactAnalysisAgent", replying("## Impacted files\n- src/Real.java\n"));
        assertThat(ok.execute(context("impact-analysis", Map.of(), Map.of(), null))
                        .artifacts())
                .containsKey("impact-analysis.md");
    }

    @Test
    @Tag("ORCH-021")
    @DisplayName("ORCH-021: the catalog serves all eight SDLC agents and rejects unknown ones")
    void catalog() {
        AgentCatalog catalog = new AgentCatalog(replying("x"));
        for (String agent : List.of(
                "RequirementsAgent",
                "ImpactAnalysisAgent",
                "DesignAgent",
                "ImplementAgent",
                "TestPlanAgent",
                "TestAgent",
                "DocsAgent",
                "ReleaseAgent")) {
            assertThat(catalog.forAgent(agent)).isNotNull();
        }
        assertThatThrownBy(() -> catalog.forAgent("HackerAgent")).isInstanceOf(IllegalArgumentException.class);
    }
}

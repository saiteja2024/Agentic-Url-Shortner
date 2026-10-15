package io.agentic.orchestrator.gate;

import static io.agentic.orchestrator.support.Stages.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.dag.RunWorkspace;
import io.agentic.orchestrator.pipeline.GateSpec;
import io.agentic.orchestrator.pipeline.StageSpec;
import io.agentic.orchestrator.workspace.GitWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltInGatesTest {

    @TempDir
    Path tmp;

    private final BuiltInGates gates = new BuiltInGates(Duration.ofSeconds(20));
    private GitWorkspace ws;
    private String checkpoint;

    @BeforeEach
    void workspace() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed/src/main"));
        Files.writeString(seed.resolve("App.java"), "class App {}\n");
        ws = GitWorkspace.create(tmp.resolve("seed"), tmp.resolve("ws"), UUID.randomUUID());
        checkpoint = ws.checkpoint();
    }

    private GateResult run(String gateId, GateSpec spec, StageSpec stage, NodeOutput output, RunWorkspace workspace)
            throws Exception {
        return gates.resolve(gateId, spec)
                .evaluate(new GateContext(
                        UUID.randomUUID(), stage.id(), gateId, spec, output, stage, workspace, checkpoint));
    }

    private GateResult run(String gateId, GateSpec spec) throws Exception {
        return run(gateId, spec, stage("implement"), NodeOutput.empty(), ws);
    }

    private static StageSpec allowing(String... globs) {
        return new StageSpec(
                "implement", "ImplementAgent", null, null, List.of(), null, null, List.of(globs), null, null);
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: command gates pass on exit 0 and fail with the output tail otherwise")
    void commandGate() throws Exception {
        assertThat(run("ok", new GateSpec("command", "echo fine", null, null, null))
                        .outcome())
                .isEqualTo(GateResult.Outcome.PASS);

        GateResult failed =
                run("tests", new GateSpec("command", "echo 'Tests run: 3, Failures: 1'; exit 1", null, null, null));
        assertThat(failed.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(failed.reason()).contains("exit 1").contains("Tests run: 3, Failures: 1");
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: command gates time out instead of hanging the run")
    void commandGateTimeout() throws Exception {
        BuiltInGates quick = new BuiltInGates(Duration.ofMillis(300));
        GateSpec spec = new GateSpec("command", "sleep 5", null, null, null);
        GateResult result = quick.resolve("slow", spec)
                .evaluate(new GateContext(
                        UUID.randomUUID(), "implement", "slow", spec, null, stage("implement"), ws, checkpoint));

        assertThat(result.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(result.reason()).contains("timed out");
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: coverage reads the JaCoCo report and enforces minLine")
    void coverageGate() throws Exception {
        GateSpec spec = new GateSpec("coverage", null, null, 0.8, null);
        assertThat(run("coverage", spec).reason()).contains("no JaCoCo report");

        Path report =
                Files.createDirectories(ws.root().resolve("target/site/jacoco")).resolve("jacoco.csv");
        Files.writeString(
                report,
                """
                GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
                p,a,A,0,10,0,0,3,7,0,1,0,1
                p,a,B,0,10,0,0,1,9,0,1,0,1
                """);
        assertThat(run("coverage", spec).outcome()).isEqualTo(GateResult.Outcome.PASS); // 16/20 = 80%

        Files.writeString(
                report,
                """
                GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
                p,a,A,0,10,0,0,5,5,0,1,0,1
                """);
        GateResult low = run("coverage", spec);
        assertThat(low.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(low.reason()).contains("50.0%").contains("80.0%");
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: allowed-paths fails on changes outside the stage's allowedPaths")
    void allowedPathsGate() throws Exception {
        GateSpec spec = new GateSpec("allowed-paths", null, null, null, null);
        Files.writeString(ws.root().resolve("src/main/Feature.java"), "class Feature {}");
        assertThat(run("allowed-paths", spec, allowing("src/main/**"), NodeOutput.empty(), ws)
                        .outcome())
                .isEqualTo(GateResult.Outcome.PASS);

        Files.writeString(ws.root().resolve("pom.xml"), "<project/>");
        GateResult outside = run("allowed-paths", spec, allowing("src/main/**"), NodeOutput.empty(), ws);
        assertThat(outside.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(outside.reason()).contains("pom.xml").doesNotContain("Feature.java");

        GateResult noneAllowed = run("allowed-paths", spec, allowing(), NodeOutput.empty(), ws);
        assertThat(noneAllowed.outcome()).isEqualTo(GateResult.Outcome.FAIL);
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: secret-scan fails on credentials in added lines without echoing them")
    void secretScanGate() throws Exception {
        GateSpec spec = new GateSpec("secret-scan", null, null, null, null);
        Files.writeString(ws.root().resolve("src/main/Clean.java"), "class Clean { String name = \"ok\"; }");
        assertThat(run("secret-scan", spec).outcome()).isEqualTo(GateResult.Outcome.PASS);

        String key = "AKIA" + "ABCDEFGHIJKLMNOP";
        Files.writeString(ws.root().resolve("src/main/Leak.java"), "class Leak { String k = \"" + key + "\"; }");
        GateResult leak = run("secret-scan", spec);
        assertThat(leak.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(leak.reason()).contains("AWS access key").doesNotContain(key);

        Files.delete(ws.root().resolve("src/main/Leak.java"));
        Files.writeString(ws.root().resolve("src/main/Pem.java"), "// -----BEGIN RSA PRIVATE KEY-----");
        assertThat(run("secret-scan", spec).reason()).contains("private key");
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: requirements gate demands REQ ids with Given/When/Then")
    void requirementsGate() throws Exception {
        GateSpec spec = new GateSpec("requirements", null, null, null, null);
        NodeOutput good = NodeOutput.of(
                Map.of(
                        "requirements.md",
                        """
                ### REQ-001 — Thing
                - **Given** x
                - **When** y
                - **Then** z
                """));
        assertThat(run("requirements-ids", spec, stage("requirements"), good, ws)
                        .outcome())
                .isEqualTo(GateResult.Outcome.PASS);

        NodeOutput vague = NodeOutput.of(Map.of("requirements.md", "Make it better."));
        assertThat(run("requirements-ids", spec, stage("requirements"), vague, ws)
                        .reason())
                .contains("no REQ-");

        NodeOutput incomplete = NodeOutput.of(Map.of("requirements.md", "### REQ-001 — Thing\n- **Given** x\n"));
        assertThat(run("requirements-ids", spec, stage("requirements"), incomplete, ws)
                        .reason())
                .contains("REQ-001")
                .contains("When")
                .contains("Then");
    }

    @Test
    @Tag("ORCH-030")
    @DisplayName("ORCH-030: unknown gate types are rejected when the run starts")
    void unknownType() {
        assertThatThrownBy(() -> gates.resolve("x", new GateSpec("nope", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }
}

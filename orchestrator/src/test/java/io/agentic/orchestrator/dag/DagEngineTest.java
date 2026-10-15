package io.agentic.orchestrator.dag;

import static io.agentic.orchestrator.support.Stages.gate;
import static io.agentic.orchestrator.support.Stages.pipeline;
import static io.agentic.orchestrator.support.Stages.stage;
import static io.agentic.orchestrator.support.Stages.when;
import static io.agentic.orchestrator.support.Stages.withGates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.common.ContentHash;
import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.gate.GateResult;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class DagEngineTest {

    private static final long TIMEOUT_S = 10;

    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final List<String> executed = new CopyOnWriteArrayList<>();
    private final Map<String, NodeExecutor> behaviour = new ConcurrentHashMap<>();
    private final Map<String, GateResult> gateResults = new ConcurrentHashMap<>();
    private final List<RunEvent> events = new CopyOnWriteArrayList<>();
    private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();

    /** Agents record that they ran, then do whatever the test configured (default: one artifact). */
    private final AgentRegistry agents = agent -> ctx -> {
        executed.add(ctx.nodeId());
        NodeExecutor custom = behaviour.get(ctx.nodeId());
        return custom != null
                ? custom.execute(ctx)
                : NodeOutput.of(Map.of(ctx.nodeId() + ".md", "output of " + ctx.nodeId()));
    };

    /** Gates return the configured result per gate id; approval-type gates come from the real registry. */
    private final GateRegistry gates = (id, spec) -> "approval".equals(spec.type())
            ? GateRegistry.defaults().resolve(id, spec)
            : ctx -> gateResults.getOrDefault(id, GateResult.pass());

    private final RunListener listener = new RunListener() {
        @Override
        public void onEvent(RunEvent event) {
            events.add(event);
        }

        @Override
        public void onArtifact(Artifact artifact) {
            artifacts.add(artifact);
        }
    };

    private final DagEngine engine = new DagEngine(pool, agents, gates, Clock.systemUTC());

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private RunStatus await(Run run) throws Exception {
        return run.completion().get(TIMEOUT_S, TimeUnit.SECONDS);
    }

    @Test
    @Tag("ORCH-004")
    @DisplayName("ORCH-004: a node runs only after every dependency is DONE")
    void runsInDependencyOrder() throws Exception {
        AtomicReference<Run> self = new AtomicReference<>();
        List<String> violations = new CopyOnWriteArrayList<>();
        behaviour.put("c", ctx -> {
            if (self.get().nodeStatus("a") != NodeStatus.DONE || self.get().nodeStatus("b") != NodeStatus.DONE) {
                violations.add("c started before its dependencies finished");
            }
            return NodeOutput.empty();
        });
        Run run =
                engine.start(pipeline(stage("c", "a", "b"), stage("a"), stage("b", "a")), RunMode.GREENFIELD, listener);
        self.set(run);

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(violations).isEmpty();
        assertThat(executed).containsExactly("a", "b", "c");
    }

    @Test
    @Tag("ORCH-005")
    @DisplayName("ORCH-005: independent branches run concurrently and re-join at a barrier")
    void parallelBranchesJoin() throws Exception {
        // Both branches must be inside their executors at the same time to pass the barrier;
        // sequential execution would time out here.
        CyclicBarrier together = new CyclicBarrier(2);
        NodeExecutor meetInMiddle = ctx -> {
            together.await(5, TimeUnit.SECONDS);
            return NodeOutput.empty();
        };
        behaviour.put("implement", meetInMiddle);
        behaviour.put("test-plan", meetInMiddle);
        AtomicReference<Run> self = new AtomicReference<>();
        List<NodeStatus> seenByTest = new CopyOnWriteArrayList<>();
        behaviour.put("test", ctx -> {
            seenByTest.add(self.get().nodeStatus("implement"));
            seenByTest.add(self.get().nodeStatus("test-plan"));
            return NodeOutput.empty();
        });

        Run run = engine.start(
                pipeline(
                        stage("design"),
                        stage("implement", "design"),
                        stage("test-plan", "design"),
                        stage("test", "implement", "test-plan")),
                RunMode.GREENFIELD,
                listener);
        self.set(run);

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(seenByTest).containsOnly(NodeStatus.DONE);
        assertThat(executed.getLast()).isEqualTo("test");
    }

    @Test
    @Tag("ORCH-007")
    @DisplayName("ORCH-007: a brownfield-only stage is skipped on a greenfield run")
    void skipsConditionalStage() throws Exception {
        Run run = engine.start(
                pipeline(
                        stage("requirements"),
                        when(stage("impact-analysis", "requirements"), "brownfield"),
                        stage("design", "requirements", "impact-analysis")),
                RunMode.GREENFIELD,
                listener);

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(executed).containsExactly("requirements", "design");
        assertThat(run.nodeStatus("impact-analysis")).isEqualTo(NodeStatus.DONE);
        assertThat(events)
                .filteredOn(e -> e.type() == EventType.NODE_SKIPPED)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.nodeId()).isEqualTo("impact-analysis");
                    assertThat(e.payload()).containsEntry("reason", "stage runs only for brownfield runs");
                });
    }

    @Test
    @Tag("ORCH-007")
    @DisplayName("ORCH-007: the same stage runs on a brownfield run")
    void runsConditionalStageWhenModeMatches() throws Exception {
        Run run = engine.start(
                pipeline(stage("requirements"), when(stage("impact-analysis", "requirements"), "brownfield")),
                RunMode.BROWNFIELD,
                listener);

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(executed).containsExactly("requirements", "impact-analysis");
    }

    @Test
    @Tag("ORCH-008")
    @DisplayName("ORCH-008: a failing entry gate fails the node before it executes")
    void entryGateFailure() throws Exception {
        gateResults.put("spec-lint", GateResult.fail("openapi.yaml: 2 errors"));
        Run run = engine.start(
                pipeline(
                        Map.of("spec-lint", gate("command")),
                        stage("requirements"),
                        withGates(stage("design", "requirements"), List.of("spec-lint"), List.of()),
                        stage("implement", "design")),
                RunMode.GREENFIELD,
                listener);

        assertThat(await(run)).isEqualTo(RunStatus.FAILED);
        assertThat(executed).containsExactly("requirements");
        assertThat(run.nodeStatus("design")).isEqualTo(NodeStatus.FAILED);
        assertThat(run.failureReason("design")).contains("spec-lint").contains("openapi.yaml: 2 errors");
        assertThat(run.nodeStatus("implement")).isEqualTo(NodeStatus.PENDING);
    }

    @Test
    @Tag("ORCH-008")
    @DisplayName("ORCH-008: a failing exit gate fails the node after it executes")
    void exitGateFailure() throws Exception {
        gateResults.put("tests-pass", GateResult.fail("3 tests failed"));
        Run run = engine.start(
                pipeline(
                        Map.of("tests-pass", gate("command")),
                        withGates(stage("test"), List.of(), List.of("tests-pass")),
                        stage("docs", "test")),
                RunMode.GREENFIELD,
                listener);

        assertThat(await(run)).isEqualTo(RunStatus.FAILED);
        assertThat(executed).containsExactly("test");
        assertThat(run.failureReason("test")).contains("3 tests failed");
        assertThat(run.nodeStatus("docs")).isEqualTo(NodeStatus.PENDING);
        assertThat(events)
                .filteredOn(e -> e.type() == EventType.GATE_EVALUATED)
                .singleElement()
                .satisfies(e -> assertThat(e.payload())
                        .containsEntry("gate", "tests-pass")
                        .containsEntry("outcome", "FAIL"));
    }

    @Test
    @Tag("ORCH-009")
    @DisplayName("ORCH-009: approval pauses the node and its dependents while other branches continue")
    void approvalPausesThenResumes() throws Exception {
        Run run = engine.start(approvalPipeline(), RunMode.GREENFIELD, listener);

        awaitStatus(run, "design", NodeStatus.WAITING_APPROVAL);
        awaitStatus(run, "test-plan-draft", NodeStatus.DONE); // independent branch kept going
        assertThat(run.nodeStatus("implement")).isEqualTo(NodeStatus.PENDING);
        assertThat(run.completion()).isNotDone();

        run.approve("design", "sai");

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(executed).contains("implement");
        assertThat(events)
                .filteredOn(e -> e.type() == EventType.APPROVAL_DECIDED)
                .singleElement()
                .satisfies(e -> assertThat(e.payload())
                        .containsEntry("decision", "APPROVED")
                        .containsEntry("by", "sai"));
    }

    @Test
    @Tag("ORCH-009")
    @DisplayName("ORCH-009: rejection fails the node and its dependents never start")
    void rejectionFailsNode() throws Exception {
        Run run = engine.start(approvalPipeline(), RunMode.GREENFIELD, listener);
        awaitStatus(run, "design", NodeStatus.WAITING_APPROVAL);

        run.reject("design", "sai", "schema change not justified");

        assertThat(await(run)).isEqualTo(RunStatus.FAILED);
        assertThat(run.nodeStatus("design")).isEqualTo(NodeStatus.FAILED);
        assertThat(run.failureReason("design")).contains("schema change not justified");
        assertThat(run.nodeStatus("implement")).isEqualTo(NodeStatus.PENDING);
        assertThat(executed).doesNotContain("implement");
    }

    @Test
    @Tag("ORCH-009")
    @DisplayName("ORCH-009: only a node that is waiting can be approved or rejected")
    void approveRequiresWaitingNode() throws Exception {
        Run run = engine.start(pipeline(stage("a")), RunMode.GREENFIELD, listener);
        await(run);

        assertThatThrownBy(() -> run.approve("a", "sai"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not waiting for approval");
        assertThatThrownBy(() -> run.reject("ghost", "sai", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @Tag("ORCH-010")
    @DisplayName("ORCH-010: a failure stops its downstream path; running siblings finish; run FAILED")
    void failureStopsDownstreamOnly() throws Exception {
        CyclicBarrier bothStarted = new CyclicBarrier(2);
        behaviour.put("implement", ctx -> {
            bothStarted.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("compiler exploded");
        });
        behaviour.put("test-plan", ctx -> {
            bothStarted.await(5, TimeUnit.SECONDS);
            Thread.sleep(100); // still running when implement fails
            return NodeOutput.empty();
        });

        Run run = engine.start(
                pipeline(
                        stage("design"),
                        stage("implement", "design"),
                        stage("test-plan", "design"),
                        stage("test", "implement", "test-plan"),
                        stage("docs", "test")),
                RunMode.GREENFIELD,
                listener);

        assertThat(await(run)).isEqualTo(RunStatus.FAILED);
        assertThat(run.nodeStatus("implement")).isEqualTo(NodeStatus.FAILED);
        assertThat(run.failureReason("implement")).contains("compiler exploded");
        assertThat(run.nodeStatus("test-plan")).isEqualTo(NodeStatus.DONE);
        assertThat(run.nodeStatus("test")).isEqualTo(NodeStatus.PENDING);
        assertThat(run.nodeStatus("docs")).isEqualTo(NodeStatus.PENDING);
        assertThat(executed).doesNotContain("test", "docs");
    }

    @Test
    @Tag("ORCH-011")
    @DisplayName("ORCH-011: artifacts carry their SHA-256 and the hashes of their parents")
    void artifactLineage() throws Exception {
        Run run = engine.start(
                pipeline(stage("requirements"), stage("test-plan"), stage("design", "requirements", "test-plan")),
                RunMode.GREENFIELD,
                listener);
        await(run);

        String reqHash = ContentHash.sha256Hex("output of requirements");
        String planHash = ContentHash.sha256Hex("output of test-plan");
        assertThat(run.artifacts())
                .filteredOn(a -> a.nodeId().equals("requirements"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.contentHash()).isEqualTo(reqHash);
                    assertThat(a.parentHashes()).isEmpty();
                });
        assertThat(run.artifacts())
                .filteredOn(a -> a.nodeId().equals("design"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.name()).isEqualTo("design.md");
                    assertThat(a.contentHash()).isEqualTo(ContentHash.sha256Hex("output of design"));
                    assertThat(a.parentHashes()).containsExactlyInAnyOrder(reqHash, planHash);
                });
        assertThat(artifacts).hasSize(3);
    }

    @Test
    @Tag("ORCH-012")
    @DisplayName("ORCH-012: every state change is an event in strictly increasing sequence")
    void auditEvents() throws Exception {
        Run run = engine.start(
                pipeline(
                        Map.of("lint", gate("command")),
                        withGates(stage("a"), List.of(), List.of("lint")),
                        stage("b", "a")),
                RunMode.GREENFIELD,
                listener);
        await(run);

        assertThat(events).extracting(RunEvent::seq).isSorted().doesNotHaveDuplicates();
        assertThat(events).allSatisfy(e -> assertThat(e.runId()).isEqualTo(run.id()));
        assertThat(events.getFirst().type()).isEqualTo(EventType.RUN_STARTED);
        assertThat(events.getLast().type()).isEqualTo(EventType.RUN_FINISHED);
        assertThat(events.getLast().payload()).containsEntry("status", "SUCCEEDED");
        assertThat(events)
                .filteredOn(e -> e.type() == EventType.NODE_TRANSITION && "a".equals(e.nodeId()))
                .extracting(e -> e.from() + "->" + e.to())
                .containsExactly("PENDING->RUNNING", "RUNNING->DONE");
        assertThat(events).anyMatch(e -> e.type() == EventType.GATE_EVALUATED && "a".equals(e.nodeId()));
        assertThat(events).anyMatch(e -> e.type() == EventType.ARTIFACT_RECORDED && "b".equals(e.nodeId()));
    }

    private PipelineDefinition approvalPipeline() {
        return pipeline(
                Map.of("human-approval", gate("approval")),
                stage("requirements"),
                withGates(stage("design", "requirements"), List.of(), List.of("human-approval")),
                stage("test-plan-draft", "requirements"),
                stage("implement", "design"));
    }

    private static void awaitStatus(Run run, String nodeId, NodeStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
        while (run.nodeStatus(nodeId) != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(nodeId + " never reached " + expected + ", is " + run.nodeStatus(nodeId));
            }
            Thread.sleep(10);
        }
    }
}

package io.agentic.orchestrator.dag;

import static io.agentic.orchestrator.support.Stages.gate;
import static io.agentic.orchestrator.support.Stages.stage;
import static io.agentic.orchestrator.support.Stages.withGates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.gate.GateResult;
import io.agentic.orchestrator.pipeline.ApprovalRule;
import io.agentic.orchestrator.pipeline.GateSpec;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.StageSpec;
import io.agentic.orchestrator.support.FakeWorkspace;
import io.agentic.orchestrator.support.Stages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class GovernanceTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private final List<String> spans = new CopyOnWriteArrayList<>();
    private final FakeWorkspace workspace = new FakeWorkspace();
    private final List<RunEvent> events = new CopyOnWriteArrayList<>();
    private final Map<String, NodeExecutor> behaviour = new ConcurrentHashMap<>();
    private final Map<String, GateResult[]> gateScript = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> gateCalls = new ConcurrentHashMap<>();
    private final List<NodeContext> contexts = new CopyOnWriteArrayList<>();

    private final AgentRegistry agents = agent -> ctx -> {
        contexts.add(ctx);
        NodeExecutor custom = behaviour.get(ctx.nodeId());
        return custom != null
                ? custom.execute(ctx)
                : NodeOutput.of(Map.of(ctx.nodeId() + ".md", "out " + ctx.nodeId()));
    };

    /** Gates return their scripted results in order (last one repeats); approval gates use the defaults. */
    private final GateRegistry gates = (id, spec) -> spec.isApproval()
            ? GateRegistry.defaults().resolve(id, spec)
            : ctx -> {
                GateResult[] script = gateScript.getOrDefault(id, new GateResult[] {GateResult.pass()});
                int call =
                        gateCalls.computeIfAbsent(id, k -> new AtomicInteger()).getAndIncrement();
                return script[Math.min(call, script.length - 1)];
            };

    private final RunListener listener = new RunListener() {
        @Override
        public void onEvent(RunEvent event) {
            events.add(event);
        }
    };

    GovernanceTest() {
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStart(Observation.Context context) {
                Observation.ContextView parent = context.getParentObservation() == null
                        ? null
                        : context.getParentObservation().getContextView();
                spans.add(context.getName() + "<" + (parent == null ? "root" : parent.getName()));
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
    }

    private final DagEngine engine =
            new DagEngine(pool, agents, gates, Clock.systemUTC(), meters, observations, timers);

    @AfterEach
    void stop() {
        pool.shutdownNow();
        timers.shutdownNow();
    }

    private static PipelineDefinition governed(
            List<ApprovalRule> policy, Map<String, GateSpec> gateSpecs, StageSpec... stages) {
        return new PipelineDefinition(
                "agentic.io/v1alpha1",
                "Pipeline",
                new PipelineDefinition.Metadata("governed", "1"),
                new PipelineDefinition.Defaults(new PipelineDefinition.Retries(3, true), "PT10M", "escalate"),
                gateSpecs,
                policy,
                List.of(stages));
    }

    private Run start(PipelineDefinition definition) {
        return start(definition, Long.MAX_VALUE, null);
    }

    private Run start(PipelineDefinition definition, long tokens, Duration time) {
        return engine.start(
                definition,
                new RunRequest(UUID.randomUUID(), "test", RunMode.GREENFIELD, "the request", tokens, time),
                workspace,
                listener);
    }

    private static RunStatus await(Run run) throws Exception {
        return run.completion().get(10, TimeUnit.SECONDS);
    }

    private static void awaitStatus(Run run, String nodeId, NodeStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (run.nodeStatus(nodeId) != expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(nodeId + " never reached " + expected + ", is " + run.nodeStatus(nodeId));
            }
            Thread.sleep(10);
        }
    }

    private List<RunEvent> eventsOf(EventType type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    // ---- retries, rollback, escalation ------------------------------------------------------

    @Test
    @Tag("ORCH-032")
    @DisplayName("ORCH-032: a failing exit gate is retried with its output fed back, from a clean checkpoint")
    void retriesWithFeedback() throws Exception {
        gateScript.put("tests", new GateResult[] {
            GateResult.fail("1 test failed: expiry"), GateResult.fail("still red"), GateResult.pass()
        });
        behaviour.put("implement", ctx -> {
            workspace.write("src/main/Attempt" + ctx.attempt() + ".java", "class A" + ctx.attempt() + " {}");
            return NodeOutput.empty();
        });

        Run run = start(governed(
                List.of(),
                Map.of("tests", gate("command")),
                withGates(stage("implement"), List.of(), List.of("tests"))));

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.attempt("implement")).isEqualTo(3);
        assertThat(eventsOf(EventType.NODE_RETRY))
                .extracting(e -> e.payload().get("reason"))
                .containsExactly(
                        "exit gate 'tests' failed: 1 test failed: expiry", "exit gate 'tests' failed: still red");
        assertThat(contexts)
                .extracting(NodeContext::feedback)
                .containsExactly(
                        null, "exit gate 'tests' failed: 1 test failed: expiry", "exit gate 'tests' failed: still red");
        // Earlier attempts' files were reset away; only the successful attempt's change remains.
        assertThat(workspace.files()).containsOnlyKeys("src/main/Attempt3.java");
        assertThat(eventsOf(EventType.NODE_RECOVERED)).hasSize(1);
    }

    @Test
    @Tag("ORCH-032")
    @Tag("ORCH-033")
    @DisplayName("ORCH-032, ORCH-033: exhausted retries roll back and escalate; approval accepts, rejection fails")
    void exhaustedRetriesRollBackAndEscalate() throws Exception {
        gateScript.put("tests", new GateResult[] {GateResult.fail("red")});
        behaviour.put("test", ctx -> {
            workspace.write("src/test/Broken.java", "broken");
            return NodeOutput.empty();
        });
        PipelineDefinition definition = governed(
                List.of(),
                Map.of("tests", gate("command")),
                withGates(stage("test"), List.of(), List.of("tests")),
                stage("docs", "test"));

        Run escalated = start(definition);
        awaitStatus(escalated, "test", NodeStatus.WAITING_APPROVAL);
        assertThat(escalated.attempt("test")).isEqualTo(3);
        assertThat(workspace.files()).doesNotContainKey("src/test/Broken.java");
        assertThat(eventsOf(EventType.ROLLBACK))
                .singleElement()
                .satisfies(e -> assertThat((String) e.payload().get("reason")).contains("retries exhausted after 3"));
        assertThat(escalated.waiting()).singleElement().satisfies(w -> assertThat(w.kind())
                .isEqualTo(Run.WaitKind.ESCALATION));

        escalated.reject("test", "sai", "needs a human fix");
        assertThat(await(escalated)).isEqualTo(RunStatus.FAILED);
        assertThat(escalated.nodeStatus("docs")).isEqualTo(NodeStatus.PENDING);
        assertThat(meters.counter("orchestrator.rollbacks", "node", "test").count())
                .isEqualTo(1);
        assertThat(meters.counter("orchestrator.retries", "node", "test").count())
                .isEqualTo(2);
        assertThat(meters.counter("orchestrator.gate.failures", "gate", "tests").count())
                .isEqualTo(3);
    }

    @Test
    @Tag("ORCH-032")
    @DisplayName("ORCH-032: approving an escalation accepts the node as DONE")
    void approvingEscalationAccepts() throws Exception {
        gateScript.put("tests", new GateResult[] {GateResult.fail("red")});
        Run run = start(governed(
                List.of(), Map.of("tests", gate("command")), withGates(stage("test"), List.of(), List.of("tests"))));
        awaitStatus(run, "test", NodeStatus.WAITING_APPROVAL);

        run.approve("test", "lead");

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @Tag("ORCH-033")
    @DisplayName("ORCH-033: rejecting an approval rolls back the node's changes")
    void rejectionRollsBack() throws Exception {
        behaviour.put("design", ctx -> {
            workspace.write("docs/design.md", "risky");
            return NodeOutput.empty();
        });
        Run run = start(governed(
                List.of(), Map.of("human", gate("approval")), withGates(stage("design"), List.of(), List.of("human"))));
        awaitStatus(run, "design", NodeStatus.WAITING_APPROVAL);
        assertThat(workspace.files()).containsKey("docs/design.md");

        run.reject("design", "lead", "too risky");

        assertThat(await(run)).isEqualTo(RunStatus.FAILED);
        assertThat(workspace.files()).isEmpty();
        assertThat(eventsOf(EventType.ROLLBACK)).hasSize(1);
    }

    // ---- policy ----------------------------------------------------------------------------------

    @Test
    @Tag("ORCH-031")
    @DisplayName("ORCH-031: a dependency change and a merge action each require approval; merge runs after it")
    void policyRequiresApproval() throws Exception {
        behaviour.put("implement", ctx -> {
            workspace.write("pom.xml", "<new dependency/>");
            return NodeOutput.empty();
        });
        behaviour.put("release", ctx -> new NodeOutput(Map.of("notes.md", "v1"), List.of(), List.of("merge"), 0, 0));
        List<ApprovalRule> policy = List.of(
                new ApprovalRule("new-dependency", List.of("**/pom.xml", "pom.xml")),
                new ApprovalRule("merge", List.of("action:merge")));
        Run run = start(governed(policy, Map.of(), stage("implement"), stage("release", "implement")));

        awaitStatus(run, "implement", NodeStatus.WAITING_APPROVAL);
        assertThat(run.waiting().getFirst().reasons()).containsExactly("policy 'new-dependency' matched pom.xml");
        run.approve("implement", "lead");

        awaitStatus(run, "release", NodeStatus.WAITING_APPROVAL);
        assertThat(run.waiting().getFirst().reasons()).containsExactly("policy 'merge' matched action:merge");
        assertThat(workspace.merged()).isFalse();
        run.approve("release", "lead");

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(workspace.merged()).isTrue();
        assertThat(eventsOf(EventType.MERGED)).hasSize(1);
    }

    // ---- clarifications ----------------------------------------------------------------------------

    @Test
    @Tag("ORCH-034")
    @DisplayName("ORCH-034: questions pause the node; answers are recorded, the rest become assumptions")
    void clarifyingQuestions() throws Exception {
        behaviour.put(
                "requirements",
                ctx -> ctx.answers().isEmpty() && ctx.attempt() == 1
                        ? new NodeOutput(
                                Map.of("requirements.md", "draft"),
                                List.of(new Question("Q1", "Vanity aliases?"), new Question("Q2", "Expiry?")),
                                List.of(),
                                0,
                                0)
                        : NodeOutput.of(Map.of("requirements.md", "final with " + ctx.answers())));
        Run run = start(governed(List.of(), Map.of(), stage("requirements"), stage("design", "requirements")));

        awaitStatus(run, "requirements", NodeStatus.WAITING_APPROVAL);
        assertThat(run.waiting().getFirst().kind()).isEqualTo(Run.WaitKind.CLARIFICATION);
        assertThat(run.waiting().getFirst().questions())
                .extracting(Question::id)
                .containsExactly("Q1", "Q2");
        assertThatThrownBy(() -> run.approve("requirements", "sai")).isInstanceOf(IllegalStateException.class);

        run.answer("requirements", "sai", Map.of("Q1", "yes, 3-32 chars"));

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.attempt("requirements")).isEqualTo(2);
        assertThat(contexts.stream()
                        .filter(c -> c.nodeId().equals("requirements"))
                        .toList()
                        .getLast()
                        .answers())
                .containsExactly(Map.entry("Q1", "yes, 3-32 chars"));
        assertThat(eventsOf(EventType.CLARIFICATION_ANSWERED)).singleElement().satisfies(e -> assertThat(e.payload())
                .containsEntry("questionId", "Q1"));
        assertThat(eventsOf(EventType.ASSUMPTION_RECORDED)).singleElement().satisfies(e -> assertThat(e.payload())
                .containsEntry("questionId", "Q2"));
        assertThat(run.artifacts())
                .anyMatch(
                        a -> a.name().equals("clarifications.md") && a.content().contains("Assumption"));
    }

    // ---- safe stop ---------------------------------------------------------------------------------

    @Test
    @Tag("ORCH-035")
    @DisplayName("ORCH-035: the kill switch cancels waiting approvals and starts nothing new")
    void killSwitch() throws Exception {
        Run run = start(governed(
                List.of(),
                Map.of("human", gate("approval")),
                withGates(stage("design"), List.of(), List.of("human")),
                stage("implement", "design")));
        awaitStatus(run, "design", NodeStatus.WAITING_APPROVAL);

        run.stop("sai", "wrong requirement");

        assertThat(await(run)).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.stopReason()).isEqualTo("wrong requirement");
        assertThat(run.nodeStatus("design")).isEqualTo(NodeStatus.FAILED);
        assertThat(run.nodeStatus("implement")).isEqualTo(NodeStatus.PENDING);
        assertThat(eventsOf(EventType.RUN_STOPPED)).singleElement().satisfies(e -> assertThat(e.payload())
                .containsEntry("by", "sai"));
        assertThatThrownBy(() -> run.stop("sai", "again")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("ORCH-035")
    @DisplayName("ORCH-035: exhausting the token budget stops the run")
    void tokenBudget() throws Exception {
        behaviour.put("a", ctx -> new NodeOutput(Map.of("a.md", "x"), List.of(), List.of(), 600, 500));
        Run run = start(governed(List.of(), Map.of(), stage("a"), stage("b", "a")), 1000, null);

        assertThat(await(run)).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.stopReason()).contains("token budget of 1000 exhausted (1100 used)");
        assertThat(run.nodeStatus("b")).isEqualTo(NodeStatus.PENDING);
        assertThat(eventsOf(EventType.LLM_USAGE)).singleElement().satisfies(e -> assertThat(e.payload())
                .containsEntry("runTotal", 1100L));
    }

    @Test
    @Tag("ORCH-035")
    @DisplayName("ORCH-035: exceeding the time budget stops the run")
    void timeBudget() throws Exception {
        Run run = start(
                governed(
                        List.of(),
                        Map.of("human", gate("approval")),
                        withGates(stage("design"), List.of(), List.of("human"))),
                Long.MAX_VALUE,
                Duration.ofMillis(300));

        assertThat(await(run)).isEqualTo(RunStatus.CANCELLED);
        assertThat(run.stopReason()).contains("time budget");
    }

    // ---- re-plan -----------------------------------------------------------------------------------

    @Test
    @Tag("ORCH-036")
    @DisplayName("ORCH-036: re-plan re-runs a changed node and its dependents only while outputs change")
    void replanRerunsOnlyWhatChanged() throws Exception {
        behaviour.put("requirements", ctx -> NodeOutput.of(Map.of("requirements.md", ctx.input())));
        behaviour.put("design", ctx -> NodeOutput.of(Map.of("design.md", "same design")));
        Run run = start(governed(
                List.of(),
                Map.of(),
                stage("requirements"),
                Stages.when(stage("impact", "requirements"), "brownfield"),
                stage("design", "requirements", "impact"),
                stage("implement", "design"),
                stage("docs")));
        await(run);
        events.clear();

        assertThatThrownBy(() -> run.replan("ghost", "sai", "x")).isInstanceOf(IllegalArgumentException.class);
        run.replan("requirements", "sai", "revised request");

        assertThat(await(run)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(eventsOf(EventType.NODE_TRANSITION).stream()
                        .filter(e -> e.to() == NodeStatus.STALE)
                        .map(RunEvent::nodeId))
                .containsExactly("requirements", "design");
        assertThat(run.attempt("requirements")).isEqualTo(2);
        assertThat(run.attempt("design")).isEqualTo(2);
        assertThat(run.attempt("implement")).isEqualTo(1); // design output unchanged → not re-run
        assertThat(run.attempt("docs")).isEqualTo(1); // not downstream at all
        assertThat(run.attempt("impact")).isZero(); // brownfield-only stage stays skipped on re-plan
        assertThat(eventsOf(EventType.REPLAN_COMPARED))
                .extracting(e -> e.nodeId() + ":" + e.payload().get("outputChanged"))
                .containsExactly("requirements:true", "design:false");
    }

    @Test
    @Tag("ORCH-036")
    @DisplayName("ORCH-036: re-plan is refused while the run is still running")
    void replanRequiresFinishedRun() throws Exception {
        Run run = start(governed(
                List.of(),
                Map.of("human", gate("approval")),
                stage("a"),
                withGates(stage("b", "a"), List.of(), List.of("human"))));
        awaitStatus(run, "b", NodeStatus.WAITING_APPROVAL);

        assertThatThrownBy(() -> run.replan("a", "sai", "x")).isInstanceOf(IllegalStateException.class);
        run.approve("b", "sai");
        await(run);
    }

    // ---- metrics and spans ---------------------------------------------------------------------------

    @Test
    @Tag("ORCH-040")
    @DisplayName("ORCH-040: runs, retries, gate failures and recovery time are measured")
    void reliabilityMetrics() throws Exception {
        gateScript.put("tests", new GateResult[] {GateResult.fail("red"), GateResult.pass()});
        Run run = start(governed(
                List.of(), Map.of("tests", gate("command")), withGates(stage("a"), List.of(), List.of("tests"))));
        await(run);

        assertThat(meters.counter("orchestrator.runs.completed", "status", "SUCCEEDED")
                        .count())
                .isEqualTo(1);
        assertThat(meters.counter("orchestrator.retries", "node", "a").count()).isEqualTo(1);
        assertThat(meters.counter("orchestrator.gate.failures", "gate", "tests").count())
                .isEqualTo(1);
        assertThat(meters.timer("orchestrator.recovery", "node", "a").count()).isEqualTo(1);
    }

    @Test
    @Tag("ORCH-041")
    @DisplayName("ORCH-041: spans nest run → node → gate and node → approval")
    void spanStructure() throws Exception {
        Run run = start(governed(
                List.of(),
                Map.of("lint", gate("check"), "human", gate("approval")),
                withGates(stage("design"), List.of(), List.of("lint", "human"))));
        awaitStatus(run, "design", NodeStatus.WAITING_APPROVAL);
        run.approve("design", "sai");
        await(run);

        assertThat(spans)
                .contains(
                        "orchestrator.run<root",
                        "orchestrator.node<orchestrator.run",
                        "orchestrator.gate<orchestrator.node",
                        "orchestrator.approval<orchestrator.node");
    }
}

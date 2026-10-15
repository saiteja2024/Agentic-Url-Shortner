package io.agentic.orchestrator.dag;

import io.agentic.common.ContentHash;
import io.agentic.common.MdcKeys;
import io.agentic.orchestrator.gate.Gate;
import io.agentic.orchestrator.gate.GateContext;
import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.gate.GateResult;
import io.agentic.orchestrator.pipeline.ApprovalRule;
import io.agentic.orchestrator.pipeline.GateSpec;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.StageSpec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

/**
 * One execution of a pipeline.
 *
 * <p>Scheduling (ORCH-004/005/010): each node is a {@link CompletableFuture} started when all of its
 * dependencies' futures complete; a failed node completes exceptionally, so its dependents never
 * start. A node waiting for a human holds an incomplete future, pausing only its own downstream path.
 *
 * <p>Governance (ORCH-030…036): exit-gate failures are retried with feedback after resetting the
 * workspace to the node's checkpoint; exhausted retries are rolled back and escalated. Policy rules
 * and approval gates pause for a human. Clarifying questions pause for answers, then re-execute.
 * The kill switch and budgets stop the run; re-plan re-executes only what changed.
 */
public final class Run {

    /** Why a node is waiting for a human. */
    public enum WaitKind {
        APPROVAL,
        ESCALATION,
        CLARIFICATION
    }

    /** A node waiting for a human, as shown by the approvals API. */
    public record Waiting(
            String nodeId, WaitKind kind, List<String> reasons, List<Question> questions, Instant since) {}

    private record Decision(
            boolean approved, boolean cancelled, String by, @Nullable String reason, Map<String, String> answers) {}

    /** Mutable state of one node execution across its attempts. */
    private static final class Execution {
        private final Node node;
        private final StageSpec stage;
        private final Observation observation;
        private final @Nullable String checkpoint;
        private int failures;
        private @Nullable Instant firstFailureAt;
        private @Nullable String feedback;
        private List<String> actions = List.of();

        Execution(Node node, StageSpec stage, Observation observation, @Nullable String checkpoint) {
            this.node = node;
            this.stage = stage;
            this.observation = observation;
            this.checkpoint = checkpoint;
        }
    }

    /** Completes a node's future exceptionally; carries no stack trace of interest. */
    private static final class NodeFailedException extends RuntimeException {
        NodeFailedException(String nodeId, String reason) {
            super("node '" + nodeId + "' failed: " + reason, null, false, false);
        }
    }

    private static final String CLARIFICATIONS = "clarifications.md";

    private final RunRequest request;
    private final PipelineDefinition definition;
    private final DagPlan plan;
    private final Executor executor;
    private final AgentRegistry agents;
    private final Clock clock;
    private final RunListener listener;
    private final RunWorkspace workspace;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final int maxAttempts;
    private final boolean escalateWhenExhausted;
    private final Instant startedAt;

    private final Map<String, StageSpec> stages = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Gate> gates = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Decision>> pendingDecisions = new ConcurrentHashMap<>();
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();
    private final Map<String, List<Artifact>> outputs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> answers = new ConcurrentHashMap<>();
    private final Map<String, String> inputOverrides = new ConcurrentHashMap<>();
    private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong tokensUsed = new AtomicLong();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile CompletableFuture<RunStatus> completion;
    private volatile Observation runObservation = Observation.NOOP;
    private volatile RunStatus status = RunStatus.RUNNING;
    private volatile @Nullable String stopReason;
    private volatile @Nullable Instant finishedAt;

    @SuppressWarnings("checkstyle:ParameterNumber")
    Run(
            PipelineDefinition definition,
            DagPlan plan,
            RunRequest request,
            RunWorkspace workspace,
            Executor executor,
            AgentRegistry agents,
            GateRegistry gateRegistry,
            Clock clock,
            RunListener listener,
            MeterRegistry meters,
            ObservationRegistry observations) {
        this.definition = definition;
        this.plan = plan;
        this.request = request;
        this.workspace = workspace;
        this.executor = executor;
        this.agents = agents;
        this.clock = clock;
        this.listener = listener;
        this.meters = meters;
        this.observations = observations;
        this.startedAt = clock.instant();
        PipelineDefinition.Defaults defaults = definition.defaults();
        this.maxAttempts = defaults != null && defaults.retries() != null
                ? Math.max(1, defaults.retries().maxAttempts())
                : 1;
        this.escalateWhenExhausted = defaults != null && "escalate".equals(defaults.onExhausted());
        for (String nodeId : plan.topologicalOrder()) {
            StageSpec stage = definition.stage(nodeId);
            stages.put(nodeId, stage);
            nodes.put(nodeId, new Node(nodeId));
            for (String gateId : concat(stage.entryGates(), stage.exitGates())) {
                gates.computeIfAbsent(
                        gateId, g -> gateRegistry.resolve(g, definition.gates().get(g)));
            }
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    void start() {
        runObservation = Observation.createNotStarted("orchestrator.run", observations)
                .contextualName("run " + request.scenario())
                .lowCardinalityKeyValue("scenario", request.scenario())
                .lowCardinalityKeyValue("mode", request.mode().name())
                .lowCardinalityKeyValue("phase", "initial")
                .highCardinalityKeyValue("run.id", id().toString())
                .start();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", definition.metadata().name());
        payload.put("version", definition.metadata().version());
        payload.put("mode", request.mode().name());
        payload.put("scenario", request.scenario());
        payload.put("request", request.request());
        payload.put("branch", workspace.branch());
        payload.put("tokenBudget", request.tokenBudget());
        emit(EventType.RUN_STARTED, null, null, null, payload);

        Map<String, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (String nodeId : plan.topologicalOrder()) {
            CompletableFuture<?>[] dependencies =
                    stages.get(nodeId).dependsOn().stream().map(futures::get).toArray(CompletableFuture[]::new);
            futures.put(
                    nodeId,
                    CompletableFuture.allOf(dependencies).thenComposeAsync(ignored -> runNode(nodeId), executor));
        }
        completion = whenAllSettled(futures.values());
    }

    private CompletableFuture<RunStatus> whenAllSettled(Iterable<CompletableFuture<Void>> futures) {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        futures.forEach(all::add);
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).handle((ignored, error) -> finish());
    }

    private RunStatus finish() {
        boolean allDone = nodes.values().stream().allMatch(n -> n.status() == NodeStatus.DONE);
        if (stopped.get()) {
            status = RunStatus.CANCELLED;
        } else {
            status = allDone ? RunStatus.SUCCEEDED : RunStatus.FAILED;
        }
        finishedAt = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.name());
        payload.put("tokensUsed", tokensUsed.get());
        if (stopReason != null) {
            payload.put("reason", stopReason);
        }
        emit(EventType.RUN_FINISHED, null, null, null, payload);
        meters.counter("orchestrator.runs.completed", "status", status.name()).increment();
        runObservation.lowCardinalityKeyValue("status", status.name()).stop();
        return status;
    }

    /**
     * Kill switch and budgets (ORCH-035): no new node or attempt starts, pending human decisions are
     * cancelled, and the run ends CANCELLED once in-flight agent calls return.
     *
     * @throws IllegalStateException if the run is not running
     */
    public void stop(String by, String reason) {
        if (status != RunStatus.RUNNING || !stopped.compareAndSet(false, true)) {
            throw new IllegalStateException("run " + id() + " is not running (" + status + ")");
        }
        stopReason = reason;
        emit(EventType.RUN_STOPPED, null, null, null, Map.of("by", by, "reason", reason));
        pendingDecisions.forEach(
                (nodeId, decision) -> decision.complete(new Decision(false, true, by, reason, Map.of())));
    }

    // ---- public API ---------------------------------------------------------------------------

    public UUID id() {
        return request.runId();
    }

    public RunRequest request() {
        return request;
    }

    public PipelineDefinition definition() {
        return definition;
    }

    public RunMode mode() {
        return request.mode();
    }

    public RunStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    @Nullable
    public Instant finishedAt() {
        return finishedAt;
    }

    @Nullable
    public String stopReason() {
        return stopReason;
    }

    public String branch() {
        return workspace.branch();
    }

    public long tokensUsed() {
        return tokensUsed.get();
    }

    /** Completes with the run's status once every node has finished or can never start. */
    public CompletableFuture<RunStatus> completion() {
        return completion;
    }

    public NodeStatus nodeStatus(String nodeId) {
        return node(nodeId).status();
    }

    @Nullable
    public String failureReason(String nodeId) {
        return node(nodeId).failureReason();
    }

    public int attempt(String nodeId) {
        return node(nodeId).attempt();
    }

    /** Node ids in topological order. */
    public List<String> nodeIds() {
        return plan.topologicalOrder();
    }

    public StageSpec stage(String nodeId) {
        node(nodeId);
        return stages.get(nodeId);
    }

    public List<Artifact> artifacts() {
        return List.copyOf(artifacts);
    }

    /** Nodes waiting for a human, in topological order. */
    public List<Waiting> waiting() {
        return plan.topologicalOrder().stream()
                .map(waiting::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** @throws IllegalStateException if the node is not waiting for an approval or escalation */
    public void approve(String nodeId, String by) {
        decide(nodeId, false, new Decision(true, false, by, null, Map.of()));
    }

    /** @throws IllegalStateException if the node is not waiting for an approval or escalation */
    public void reject(String nodeId, String by, String reason) {
        decide(nodeId, false, new Decision(false, false, by, reason, Map.of()));
    }

    /** @throws IllegalStateException if the node is not waiting for clarification */
    public void answer(String nodeId, String by, Map<String, String> questionAnswers) {
        decide(nodeId, true, new Decision(true, false, by, null, questionAnswers));
    }

    // ---- node execution -----------------------------------------------------------------------

    private CompletableFuture<Void> runNode(String nodeId) {
        Node node = nodes.get(nodeId);
        StageSpec stage = stages.get(nodeId);
        if (stopped.get()) {
            // The node never starts and stays PENDING (ORCH-035).
            return CompletableFuture.failedFuture(new NodeFailedException(nodeId, "run stopped"));
        }
        if (!applies(stage)) {
            transition(node, NodeStatus.DONE, Map.of());
            emit(
                    EventType.NODE_SKIPPED,
                    nodeId,
                    null,
                    null,
                    Map.of("reason", "stage runs only for " + stage.when() + " runs"));
            return CompletableFuture.completedFuture(null);
        }
        transition(node, NodeStatus.RUNNING, Map.of());
        return execute(newExecution(node, stage));
    }

    private Execution newExecution(Node node, StageSpec stage) {
        Observation observation = Observation.createNotStarted("orchestrator.node", observations)
                .contextualName("node " + node.id())
                .parentObservation(runObservation)
                .lowCardinalityKeyValue("node", node.id())
                .highCardinalityKeyValue("run.id", id().toString())
                .start();
        return new Execution(node, stage, observation, workspace.checkpoint());
    }

    /** Runs the execution's attempts and closes its span with the node's final result. */
    private CompletableFuture<Void> execute(Execution exec) {
        return attempt(exec).whenComplete((ignored, error) -> exec.observation
                .lowCardinalityKeyValue("result", exec.node.status().name())
                .highCardinalityKeyValue("attempts", String.valueOf(exec.node.attempt()))
                .stop());
    }

    /** One attempt, with runId/nodeId in the MDC (OBS-003) and the node span in scope (ORCH-041). */
    private CompletableFuture<Void> attempt(Execution exec) {
        MDC.put(MdcKeys.RUN_ID, id().toString());
        MDC.put(MdcKeys.NODE_ID, exec.node.id());
        try (Observation.Scope ignored = exec.observation.openScope()) {
            return attemptInScope(exec);
        } finally {
            MDC.remove(MdcKeys.RUN_ID);
            MDC.remove(MdcKeys.NODE_ID);
        }
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private CompletableFuture<Void> attemptInScope(Execution exec) {
        Node node = exec.node;
        StageSpec stage = exec.stage;
        if (stopped.get()) {
            return fail(exec, "run stopped: " + stopReason);
        }
        try {
            for (String gateId : stage.entryGates()) {
                GateResult result = evaluate(exec, gateId, "entry", null);
                if (result.outcome() == GateResult.Outcome.FAIL) {
                    // Entry gates check preconditions the agent can't fix: no retry.
                    return fail(exec, "entry gate '" + gateId + "' failed: " + result.reason());
                }
            }

            NodeOutput output;
            try {
                output = agents.forAgent(stage.agent()).execute(context(exec));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return fail(exec, "interrupted");
            } catch (Exception e) {
                String message =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                return retryOrEscalate(exec, "agent '" + stage.agent() + "' failed: " + message);
            }
            if (spendTokens(node, output)) {
                return fail(exec, "token budget exhausted");
            }
            if (stopped.get()) {
                return fail(exec, "run stopped: " + stopReason);
            }

            if (!output.questions().isEmpty() && !answers.containsKey(node.id())) {
                record(stage, output);
                return awaitHuman(
                        exec, WaitKind.CLARIFICATION, List.of("the request is ambiguous"), output.questions());
            }

            List<String> approvalReasons = new ArrayList<>();
            for (String gateId : stage.exitGates()) {
                if (definition.gates().get(gateId).isApproval()) {
                    continue; // approvals come after every machine check passed (ORCH-008)
                }
                GateResult result = evaluate(exec, gateId, "exit", output);
                if (result.outcome() == GateResult.Outcome.FAIL) {
                    meters.counter("orchestrator.gate.failures", "gate", gateId).increment();
                    return retryOrEscalate(exec, "exit gate '" + gateId + "' failed: " + result.reason());
                }
                if (result.outcome() == GateResult.Outcome.NEEDS_APPROVAL) {
                    approvalReasons.add(gateId + ": " + result.reason());
                }
            }
            for (String gateId : stage.exitGates()) {
                if (definition.gates().get(gateId).isApproval()) {
                    GateResult result = evaluate(exec, gateId, "exit", output);
                    if (result.outcome() != GateResult.Outcome.PASS) {
                        approvalReasons.add(gateId + ": " + result.reason());
                    }
                }
            }
            approvalReasons.addAll(policyMatches(exec, output));
            exec.actions = output.actions();

            record(stage, output);
            String commit = workspace.commit("node " + node.id() + " attempt " + node.attempt());
            recordRecovery(exec);
            if (!approvalReasons.isEmpty()) {
                return awaitHuman(exec, WaitKind.APPROVAL, approvalReasons, List.of());
            }
            Map<String, Object> done = new LinkedHashMap<>();
            if (commit != null) {
                done.put("commit", commit);
            }
            transition(node, NodeStatus.DONE, done);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return fail(exec, "orchestrator error: " + message);
        }
    }

    private NodeContext context(Execution exec) {
        String nodeId = exec.node.id();
        return new NodeContext(
                id(),
                nodeId,
                exec.stage,
                mode(),
                request.scenario(),
                inputOverrides.getOrDefault(nodeId, request.request()),
                exec.node.attempt(),
                upstreamOf(exec.stage),
                answers.getOrDefault(nodeId, Map.of()),
                exec.feedback,
                workspace.root());
    }

    /** Retries with the failure fed back (ORCH-032); when exhausted, rolls back (ORCH-033) and escalates. */
    private CompletableFuture<Void> retryOrEscalate(Execution exec, String reason) {
        exec.failures++;
        if (exec.firstFailureAt == null) {
            exec.firstFailureAt = clock.instant();
        }
        if (exec.failures < maxAttempts && !stopped.get()) {
            if (exec.checkpoint != null) {
                workspace.reset(exec.checkpoint);
            }
            int next = exec.node.nextAttempt();
            exec.feedback = reason;
            meters.counter("orchestrator.retries", "node", exec.node.id()).increment();
            emit(
                    EventType.NODE_RETRY,
                    exec.node.id(),
                    null,
                    null,
                    Map.of("attempt", next, "reason", reason, "maxAttempts", maxAttempts));
            return attempt(exec);
        }
        rollback(exec, "retries exhausted after " + exec.failures + " attempt(s): " + reason);
        if (escalateWhenExhausted && !stopped.get()) {
            return awaitHuman(
                    exec,
                    WaitKind.ESCALATION,
                    List.of("retries exhausted after " + exec.failures
                            + " attempt(s); changes rolled back. Last failure: " + reason),
                    List.of());
        }
        return fail(exec, reason);
    }

    private void rollback(Execution exec, String reason) {
        if (exec.checkpoint == null) {
            return;
        }
        workspace.reset(exec.checkpoint);
        meters.counter("orchestrator.rollbacks", "node", exec.node.id()).increment();
        emit(EventType.ROLLBACK, exec.node.id(), null, null, Map.of("checkpoint", exec.checkpoint, "reason", reason));
    }

    /** Pauses for a human (ORCH-009/031/032/034) without holding a thread. */
    private CompletableFuture<Void> awaitHuman(
            Execution exec, WaitKind kind, List<String> reasons, List<Question> questions) {
        Node node = exec.node;
        CompletableFuture<Decision> decision = new CompletableFuture<>();
        Waiting wait = new Waiting(node.id(), kind, List.copyOf(reasons), List.copyOf(questions), clock.instant());
        waiting.put(node.id(), wait);
        pendingDecisions.put(node.id(), decision);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind.name());
        payload.put("reasons", reasons);
        if (!questions.isEmpty()) {
            payload.put(
                    "questions",
                    questions.stream()
                            .map(q -> Map.of("id", q.id(), "text", q.text()))
                            .toList());
        }
        transition(node, NodeStatus.WAITING_APPROVAL, payload);
        Observation approval = Observation.createNotStarted("orchestrator.approval", observations)
                .contextualName("approval " + node.id())
                .parentObservation(exec.observation)
                .lowCardinalityKeyValue("node", node.id())
                .lowCardinalityKeyValue("kind", kind.name())
                .start();
        if (stopped.get() && pendingDecisions.remove(node.id()) != null) {
            decision.complete(new Decision(false, true, "system", stopReason, Map.of()));
        }
        return decision.thenComposeAsync(d -> decided(exec, wait, d, approval), executor);
    }

    private CompletableFuture<Void> decided(Execution exec, Waiting wait, Decision d, Observation approval) {
        Node node = exec.node;
        waiting.remove(node.id());
        String outcome;
        if (d.cancelled()) {
            outcome = "CANCELLED";
        } else if (wait.kind() == WaitKind.CLARIFICATION) {
            outcome = "ANSWERED";
        } else {
            outcome = d.approved() ? "APPROVED" : "REJECTED";
        }
        approval.lowCardinalityKeyValue("decision", outcome).stop();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", wait.kind().name());
        payload.put("decision", outcome);
        payload.put("by", d.by());
        payload.put("waitMs", Duration.between(wait.since(), clock.instant()).toMillis());
        if (d.reason() != null) {
            payload.put("reason", d.reason());
        }
        emit(EventType.APPROVAL_DECIDED, node.id(), null, null, payload);

        if (d.cancelled()) {
            return fail(exec, "cancelled while waiting: " + d.reason());
        }
        if (wait.kind() == WaitKind.CLARIFICATION) {
            recordClarifications(exec, wait.questions(), d.answers(), d.by());
            transition(node, NodeStatus.RUNNING, Map.of("reason", "re-executing with answers"));
            return attempt(exec);
        }
        if (!d.approved()) {
            if (wait.kind() == WaitKind.APPROVAL) {
                rollback(exec, "rejected by " + d.by() + ": " + d.reason());
            }
            return fail(exec, "rejected by " + d.by() + ": " + d.reason());
        }
        if (wait.kind() == WaitKind.APPROVAL && exec.actions.contains("merge")) {
            workspace.merge();
            emit(EventType.MERGED, node.id(), null, null, Map.of("branch", workspace.branch(), "by", d.by()));
        }
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("approvedBy", d.by());
        if (wait.kind() == WaitKind.ESCALATION) {
            done.put("reason", "accepted by a human after escalation");
        }
        transition(node, NodeStatus.DONE, done);
        return CompletableFuture.completedFuture(null);
    }

    /** Answers become an artifact; unanswered questions become explicit assumptions (ORCH-034). */
    private void recordClarifications(Execution exec, List<Question> questions, Map<String, String> given, String by) {
        Map<String, String> accepted = new LinkedHashMap<>();
        StringBuilder doc = new StringBuilder("# Clarifications\n\n");
        for (Question q : questions) {
            String answer = given.get(q.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("questionId", q.id());
            payload.put("question", q.text());
            payload.put("by", by);
            doc.append("- **").append(q.id()).append("** ").append(q.text()).append('\n');
            if (answer != null && !answer.isBlank()) {
                accepted.put(q.id(), answer);
                payload.put("answer", answer);
                emit(EventType.CLARIFICATION_ANSWERED, exec.node.id(), null, null, payload);
                doc.append("  - Answer: ").append(answer).append('\n');
            } else {
                String assumption = "Unanswered — treated as out of scope for this run.";
                payload.put("assumption", assumption);
                emit(EventType.ASSUMPTION_RECORDED, exec.node.id(), null, null, payload);
                doc.append("  - Assumption: ").append(assumption).append('\n');
            }
        }
        answers.put(exec.node.id(), accepted);
        record(exec.stage, NodeOutput.of(Map.of(CLARIFICATIONS, doc.toString())));
    }

    private void decide(String nodeId, boolean clarification, Decision decision) {
        Node node = node(nodeId);
        Waiting wait = waiting.get(nodeId);
        boolean kindMatches = wait != null && (wait.kind() == WaitKind.CLARIFICATION) == clarification;
        if (wait == null || node.status() != NodeStatus.WAITING_APPROVAL || !kindMatches) {
            throw new IllegalStateException("node '" + nodeId + "' is not waiting for "
                    + (clarification ? "clarification" : "approval") + " (" + node.status() + ")");
        }
        CompletableFuture<Decision> pending = pendingDecisions.remove(nodeId);
        if (pending == null) {
            throw new IllegalStateException("node '" + nodeId + "' was already decided");
        }
        pending.complete(decision);
    }

    // ---- re-plan (ORCH-036) -------------------------------------------------------------------

    /**
     * Re-executes {@code nodeId} with new input, then re-executes each dependent only if one of its
     * dependencies' output hashes changed.
     *
     * @throws IllegalStateException if the run is still running or the node is not DONE
     */
    public synchronized void replan(String nodeId, String by, String input) {
        Node root = node(nodeId);
        if (status == RunStatus.RUNNING || root.status() != NodeStatus.DONE) {
            throw new IllegalStateException(
                    "can only re-plan a DONE node of a finished run (run " + status + ", node " + root.status() + ")");
        }
        status = RunStatus.RUNNING;
        finishedAt = null;
        inputOverrides.put(nodeId, input);
        runObservation = Observation.createNotStarted("orchestrator.run", observations)
                .contextualName("replan " + nodeId)
                .lowCardinalityKeyValue("scenario", request.scenario())
                .lowCardinalityKeyValue("mode", request.mode().name())
                .lowCardinalityKeyValue("phase", "replan")
                .highCardinalityKeyValue("run.id", id().toString())
                .start();
        emit(EventType.REPLAN_STARTED, nodeId, null, null, Map.of("by", by, "input", input));

        Set<String> affected = descendantsOf(nodeId);
        Map<String, Boolean> changed = new ConcurrentHashMap<>();
        Map<String, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (String id : plan.topologicalOrder()) {
            if (!affected.contains(id)) {
                continue;
            }
            List<String> deps = stages.get(id).dependsOn();
            CompletableFuture<?>[] depFutures =
                    deps.stream().filter(futures::containsKey).map(futures::get).toArray(CompletableFuture[]::new);
            boolean isRoot = id.equals(nodeId);
            futures.put(
                    id,
                    CompletableFuture.allOf(depFutures)
                            .thenComposeAsync(
                                    ignored -> {
                                        boolean upstreamChanged =
                                                isRoot || deps.stream().anyMatch(d -> changed.getOrDefault(d, false));
                                        if (!upstreamChanged || stopped.get() || !applies(stages.get(id))) {
                                            changed.put(id, false);
                                            return CompletableFuture.completedFuture(null);
                                        }
                                        String reason =
                                                isRoot ? "re-plan requested by " + by : "upstream output changed";
                                        return rerun(id, reason, changed);
                                    },
                                    executor));
        }
        completion = whenAllSettled(futures.values());
    }

    private CompletableFuture<Void> rerun(String nodeId, String reason, Map<String, Boolean> changed) {
        Node node = nodes.get(nodeId);
        List<String> before = hashes(nodeId);
        transition(node, NodeStatus.STALE, Map.of("reason", reason));
        transition(node, NodeStatus.RUNNING, Map.of("reason", "re-plan"));
        outputs.remove(nodeId);
        return execute(newExecution(node, stages.get(nodeId))).thenRun(() -> {
            boolean differs = !before.equals(hashes(nodeId));
            changed.put(nodeId, differs);
            emit(
                    EventType.REPLAN_COMPARED,
                    nodeId,
                    null,
                    null,
                    Map.of("outputChanged", differs, "downstream", differs ? "re-run" : "kept"));
        });
    }

    /** Conditional stages (ORCH-007) stay skipped on re-plan too. */
    private boolean applies(StageSpec stage) {
        return stage.when() == null || mode().matches(stage.when());
    }

    private List<String> hashes(String nodeId) {
        return outputs.getOrDefault(nodeId, List.of()).stream()
                .filter(a -> !a.name().equals(CLARIFICATIONS))
                .map(Artifact::contentHash)
                .sorted()
                .toList();
    }

    private Set<String> descendantsOf(String nodeId) {
        Set<String> result = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>(List.of(nodeId));
        while (!frontier.isEmpty()) {
            String current = frontier.removeFirst();
            if (result.add(current)) {
                frontier.addAll(plan.dependents(current));
            }
        }
        return result;
    }

    // ---- gates, policy, artifacts, budgets ----------------------------------------------------

    private GateResult evaluate(Execution exec, String gateId, String phase, @Nullable NodeOutput output)
            throws Exception {
        GateSpec spec = definition.gates().get(gateId);
        Observation observation = Observation.createNotStarted("orchestrator.gate", observations)
                .contextualName("gate " + gateId)
                .parentObservation(exec.observation)
                .lowCardinalityKeyValue("gate", gateId)
                .lowCardinalityKeyValue("phase", phase)
                .highCardinalityKeyValue("attempt", String.valueOf(exec.node.attempt()))
                .start();
        GateResult result;
        try (Observation.Scope ignored = observation.openScope()) {
            result = gates.get(gateId)
                    .evaluate(new GateContext(
                            id(), exec.node.id(), gateId, spec, output, exec.stage, workspace, exec.checkpoint));
        } catch (Exception e) {
            observation.error(e);
            observation.stop();
            throw e;
        }
        observation.lowCardinalityKeyValue("outcome", result.outcome().name()).stop();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gate", gateId);
        payload.put("type", spec.type());
        payload.put("phase", phase);
        payload.put("outcome", result.outcome().name());
        payload.put("attempt", exec.node.attempt());
        if (result.reason() != null) {
            payload.put("reason", result.reason());
        }
        emit(EventType.GATE_EVALUATED, exec.node.id(), null, null, payload);
        return result;
    }

    /** Approval-policy rules matched by the node's changed paths or requested actions (ORCH-031). */
    private List<String> policyMatches(Execution exec, NodeOutput output) {
        List<String> changedPaths = exec.checkpoint == null ? List.of() : workspace.changedPaths(exec.checkpoint);
        List<String> reasons = new ArrayList<>();
        for (ApprovalRule rule : definition.approvalPolicy()) {
            List<String> hits = new ArrayList<>();
            for (String pattern : rule.match()) {
                if (pattern.startsWith("action:")) {
                    if (output.actions().contains(pattern.substring("action:".length()))) {
                        hits.add(pattern);
                    }
                } else {
                    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                    changedPaths.stream()
                            .filter(p -> matcher.matches(Path.of(p)))
                            .forEach(hits::add);
                }
            }
            if (!hits.isEmpty()) {
                reasons.add("policy '" + rule.id() + "' matched " + String.join(", ", hits));
                emit(EventType.POLICY_MATCHED, exec.node.id(), null, null, Map.of("rule", rule.id(), "matches", hits));
            }
        }
        return reasons;
    }

    /** Adds the attempt's tokens; returns true (and stops the run) if the budget is exhausted. */
    private boolean spendTokens(Node node, NodeOutput output) {
        long spent = output.inputTokens() + output.outputTokens();
        if (spent == 0) {
            return false;
        }
        long total = tokensUsed.addAndGet(spent);
        emit(
                EventType.LLM_USAGE,
                node.id(),
                null,
                null,
                Map.of(
                        "attempt", node.attempt(),
                        "inputTokens", output.inputTokens(),
                        "outputTokens", output.outputTokens(),
                        "runTotal", total));
        meters.counter("orchestrator.llm.tokens", "node", node.id()).increment(spent);
        if (total > request.tokenBudget() && !stopped.get()) {
            stop("system", "token budget of " + request.tokenBudget() + " exhausted (" + total + " used)");
            return true;
        }
        return false;
    }

    /** MTTR (ORCH-040): time from a node's first failure to its recovery. */
    private void recordRecovery(Execution exec) {
        if (exec.firstFailureAt != null) {
            Duration recovery = Duration.between(exec.firstFailureAt, clock.instant());
            meters.timer("orchestrator.recovery", "node", exec.node.id()).record(recovery);
            emit(
                    EventType.NODE_RECOVERED,
                    exec.node.id(),
                    null,
                    null,
                    Map.of("afterAttempts", exec.failures + 1, "recoveryMs", recovery.toMillis()));
        }
    }

    private void record(StageSpec stage, NodeOutput output) {
        List<String> parentHashes = stage.dependsOn().stream()
                .flatMap(dependency -> outputs.getOrDefault(dependency, List.of()).stream())
                .map(Artifact::contentHash)
                .sorted()
                .toList();
        List<Artifact> produced = new ArrayList<>(outputs.getOrDefault(stage.id(), List.of()));
        output.sorted().forEach((name, content) -> {
            Artifact artifact =
                    new Artifact(id(), stage.id(), name, content, ContentHash.sha256Hex(content), parentHashes);
            produced.removeIf(a -> a.name().equals(name));
            produced.add(artifact);
            artifacts.add(artifact);
            synchronized (this) {
                listener.onArtifact(artifact);
            }
            emit(
                    EventType.ARTIFACT_RECORDED,
                    stage.id(),
                    null,
                    null,
                    Map.of("name", name, "contentHash", artifact.contentHash()));
        });
        outputs.put(stage.id(), List.copyOf(produced));
    }

    private Map<String, List<Artifact>> upstreamOf(StageSpec stage) {
        Map<String, List<Artifact>> upstream = new LinkedHashMap<>();
        for (String dependency : stage.dependsOn()) {
            upstream.put(dependency, outputs.getOrDefault(dependency, List.of()));
        }
        return upstream;
    }

    private CompletableFuture<Void> fail(Execution exec, String reason) {
        exec.node.recordFailure(reason);
        transition(exec.node, NodeStatus.FAILED, Map.of("reason", reason));
        return CompletableFuture.failedFuture(new NodeFailedException(exec.node.id(), reason));
    }

    // ---- events -------------------------------------------------------------------------------

    private void transition(Node node, NodeStatus next, Map<String, Object> extra) {
        synchronized (this) {
            NodeStatus previous = node.transitionTo(next);
            Map<String, Object> payload = new LinkedHashMap<>(extra);
            payload.put("attempt", node.attempt());
            emitLocked(EventType.NODE_TRANSITION, node.id(), previous, next, payload);
        }
    }

    private void emit(
            EventType type,
            @Nullable String nodeId,
            @Nullable NodeStatus from,
            @Nullable NodeStatus to,
            Map<String, Object> payload) {
        synchronized (this) {
            emitLocked(type, nodeId, from, to, payload);
        }
    }

    /** Sequence numbers are assigned and delivered under the run's lock, so listeners see them in order. */
    private void emitLocked(
            EventType type,
            @Nullable String nodeId,
            @Nullable NodeStatus from,
            @Nullable NodeStatus to,
            Map<String, Object> payload) {
        listener.onEvent(
                new RunEvent(sequence.incrementAndGet(), id(), nodeId, type, from, to, payload, clock.instant()));
    }

    private Node node(String nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("unknown node '" + nodeId + "'");
        }
        return node;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}

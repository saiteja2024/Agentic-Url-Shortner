package io.agentic.orchestrator.dag;

import io.agentic.common.ContentHash;
import io.agentic.common.MdcKeys;
import io.agentic.orchestrator.gate.Gate;
import io.agentic.orchestrator.gate.GateContext;
import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.gate.GateResult;
import io.agentic.orchestrator.pipeline.GateSpec;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.StageSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

/**
 * One execution of a pipeline. Each node is a {@link CompletableFuture} that starts when all of its
 * dependencies' futures complete (join barrier, ORCH-005); independent nodes therefore run in
 * parallel on the engine's executor. A failed node completes exceptionally, so its dependents never
 * start (ORCH-010). A node waiting for approval holds an incomplete future, pausing only its own
 * downstream path (ORCH-009).
 */
public final class Run {

    /** A human decision on a node waiting for approval. */
    private record Decision(boolean approved, String by, @Nullable String reason) {}

    /** Completes a node's future exceptionally; carries no stack trace of interest. */
    private static final class NodeFailedException extends RuntimeException {
        NodeFailedException(String nodeId, String reason) {
            super("node '" + nodeId + "' failed: " + reason, null, false, false);
        }
    }

    private final UUID id = UUID.randomUUID();
    private final PipelineDefinition definition;
    private final RunMode mode;
    private final Executor executor;
    private final AgentRegistry agents;
    private final Clock clock;
    private final RunListener listener;
    private final Map<String, StageSpec> stages = new LinkedHashMap<>();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Gate> gates = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Decision>> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, List<Artifact>> outputs = new ConcurrentHashMap<>();
    private final List<Artifact> artifacts = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final DagPlan plan;
    private CompletableFuture<RunStatus> completion;
    private volatile RunStatus status = RunStatus.RUNNING;

    Run(
            PipelineDefinition definition,
            DagPlan plan,
            RunMode mode,
            Executor executor,
            AgentRegistry agents,
            GateRegistry gateRegistry,
            Clock clock,
            RunListener listener) {
        this.definition = definition;
        this.plan = plan;
        this.mode = mode;
        this.executor = executor;
        this.agents = agents;
        this.clock = clock;
        this.listener = listener;
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

    void start() {
        emit(
                EventType.RUN_STARTED,
                null,
                null,
                null,
                Map.of(
                        "pipeline", definition.metadata().name(),
                        "version", definition.metadata().version(),
                        "mode", mode.name()));
        Map<String, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (String nodeId : plan.topologicalOrder()) {
            CompletableFuture<?>[] dependencies =
                    stages.get(nodeId).dependsOn().stream().map(futures::get).toArray(CompletableFuture[]::new);
            futures.put(
                    nodeId,
                    CompletableFuture.allOf(dependencies).thenComposeAsync(ignored -> runNode(nodeId), executor));
        }
        completion = CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> finish());
    }

    // ---- public API ---------------------------------------------------------------------

    public UUID id() {
        return id;
    }

    public PipelineDefinition definition() {
        return definition;
    }

    public RunMode mode() {
        return mode;
    }

    public RunStatus status() {
        return status;
    }

    /** Completes with SUCCEEDED or FAILED once every node has finished or can never start. */
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

    public List<Artifact> artifacts() {
        return List.copyOf(artifacts);
    }

    /** @throws IllegalStateException if the node is not waiting for approval */
    public void approve(String nodeId, String approver) {
        decide(nodeId, new Decision(true, approver, null));
    }

    /** @throws IllegalStateException if the node is not waiting for approval */
    public void reject(String nodeId, String approver, String reason) {
        decide(nodeId, new Decision(false, approver, reason));
    }

    // ---- node execution -----------------------------------------------------------------

    /** Runs one node with runId/nodeId in the logging MDC (OBS-003). */
    private CompletableFuture<Void> runNode(String nodeId) {
        MDC.put(MdcKeys.RUN_ID, id.toString());
        MDC.put(MdcKeys.NODE_ID, nodeId);
        try {
            return executeNode(nodeId);
        } finally {
            MDC.remove(MdcKeys.RUN_ID);
            MDC.remove(MdcKeys.NODE_ID);
        }
    }

    private CompletableFuture<Void> executeNode(String nodeId) {
        Node node = nodes.get(nodeId);
        StageSpec stage = stages.get(nodeId);
        if (stage.when() != null && !mode.matches(stage.when())) {
            transition(node, NodeStatus.DONE, Map.of());
            emit(
                    EventType.NODE_SKIPPED,
                    nodeId,
                    null,
                    null,
                    Map.of("reason", "stage runs only for " + stage.when() + " runs"));
            return CompletableFuture.completedFuture(null);
        }
        try {
            transition(node, NodeStatus.RUNNING, Map.of());
            for (String gateId : stage.entryGates()) {
                GateResult result = evaluate(nodeId, gateId, "entry", null);
                if (result.outcome() == GateResult.Outcome.FAIL) {
                    return fail(node, "entry gate '" + gateId + "' failed: " + result.reason());
                }
            }

            NodeOutput output = agents.forAgent(stage.agent())
                    .execute(new NodeContext(id, nodeId, stage, mode, node.attempt(), upstreamOf(stage)));
            record(stage, output);

            List<String> approvalReasons = new ArrayList<>();
            for (String gateId : stage.exitGates()) {
                GateResult result = evaluate(nodeId, gateId, "exit", output);
                switch (result.outcome()) {
                    case FAIL -> {
                        return fail(node, "exit gate '" + gateId + "' failed: " + result.reason());
                    }
                    case NEEDS_APPROVAL -> approvalReasons.add(gateId + ": " + result.reason());
                    case PASS -> {
                        // continue with the next gate
                    }
                    default -> throw new IllegalStateException("unknown outcome " + result.outcome());
                }
            }
            if (approvalReasons.isEmpty()) {
                transition(node, NodeStatus.DONE, Map.of());
                return CompletableFuture.completedFuture(null);
            }
            return awaitApproval(node, approvalReasons);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(node, "interrupted");
        } catch (Exception e) {
            String message =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return fail(node, "agent '" + stage.agent() + "' failed: " + message);
        }
    }

    private CompletableFuture<Void> awaitApproval(Node node, List<String> reasons) {
        CompletableFuture<Decision> decision = new CompletableFuture<>();
        pendingApprovals.put(node.id(), decision);
        transition(node, NodeStatus.WAITING_APPROVAL, Map.of("awaiting", String.join("; ", reasons)));
        return decision.thenCompose(d -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision", d.approved() ? "APPROVED" : "REJECTED");
            payload.put("by", d.by());
            if (d.reason() != null) {
                payload.put("reason", d.reason());
            }
            emit(EventType.APPROVAL_DECIDED, node.id(), null, null, payload);
            if (d.approved()) {
                transition(node, NodeStatus.DONE, Map.of());
                return CompletableFuture.<Void>completedFuture(null);
            }
            return fail(node, "rejected by " + d.by() + ": " + d.reason());
        });
    }

    private void decide(String nodeId, Decision decision) {
        Node node = node(nodeId);
        CompletableFuture<Decision> pending = pendingApprovals.remove(nodeId);
        if (pending == null || node.status() != NodeStatus.WAITING_APPROVAL) {
            throw new IllegalStateException(
                    "node '" + nodeId + "' is not waiting for approval (" + node.status() + ")");
        }
        pending.complete(decision);
    }

    private GateResult evaluate(String nodeId, String gateId, String phase, @Nullable NodeOutput output)
            throws Exception {
        GateSpec spec = definition.gates().get(gateId);
        GateResult result = gates.get(gateId).evaluate(new GateContext(id, nodeId, gateId, spec, output));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("gate", gateId);
        payload.put("type", spec.type());
        payload.put("phase", phase);
        payload.put("outcome", result.outcome().name());
        if (result.reason() != null) {
            payload.put("reason", result.reason());
        }
        emit(EventType.GATE_EVALUATED, nodeId, null, null, payload);
        return result;
    }

    private void record(StageSpec stage, NodeOutput output) {
        List<String> parentHashes = stage.dependsOn().stream()
                .flatMap(dependency -> outputs.getOrDefault(dependency, List.of()).stream())
                .map(Artifact::contentHash)
                .sorted()
                .toList();
        List<Artifact> produced = new ArrayList<>();
        output.sorted().forEach((name, content) -> {
            Artifact artifact =
                    new Artifact(id, stage.id(), name, content, ContentHash.sha256Hex(content), parentHashes);
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

    private CompletableFuture<Void> fail(Node node, String reason) {
        node.recordFailure(reason);
        transition(node, NodeStatus.FAILED, Map.of("reason", reason));
        return CompletableFuture.failedFuture(new NodeFailedException(node.id(), reason));
    }

    private RunStatus finish() {
        boolean allDone = nodes.values().stream().allMatch(n -> n.status() == NodeStatus.DONE);
        status = allDone ? RunStatus.SUCCEEDED : RunStatus.FAILED;
        emit(EventType.RUN_FINISHED, null, null, null, Map.of("status", status.name()));
        return status;
    }

    // ---- events -------------------------------------------------------------------------

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
                new RunEvent(sequence.incrementAndGet(), id, nodeId, type, from, to, payload, clock.instant()));
    }

    private Node node(String nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("unknown node '" + nodeId + "'");
        }
        return node;
    }

    private static List<String> concat(Collection<String> a, Collection<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}

package io.agentic.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentic.orchestrator.dag.Run;
import io.agentic.orchestrator.generated.model.ApprovalReport;
import io.agentic.orchestrator.generated.model.ArtifactReport;
import io.agentic.orchestrator.generated.model.AuditEvent;
import io.agentic.orchestrator.generated.model.ClarificationReport;
import io.agentic.orchestrator.generated.model.GateReport;
import io.agentic.orchestrator.generated.model.Mode;
import io.agentic.orchestrator.generated.model.NodeReport;
import io.agentic.orchestrator.generated.model.NodeState;
import io.agentic.orchestrator.generated.model.NodeView;
import io.agentic.orchestrator.generated.model.PendingApproval;
import io.agentic.orchestrator.generated.model.Question;
import io.agentic.orchestrator.generated.model.RollbackReport;
import io.agentic.orchestrator.generated.model.RunDetail;
import io.agentic.orchestrator.generated.model.RunReport;
import io.agentic.orchestrator.generated.model.RunState;
import io.agentic.orchestrator.generated.model.RunSummary;
import io.agentic.orchestrator.generated.model.TokenReport;
import io.agentic.orchestrator.generated.model.WaitKind;
import io.agentic.orchestrator.pipeline.StageSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Read models for the API. Live runs are answered from memory; everything else, including the
 * report (ORCH-042) and audit log (ORCH-043), is rebuilt from the database, so the report is only
 * as complete as the audit trail it is derived from.
 */
@Service
public class RunQueries {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};
    private static final TypeReference<List<String>> JSON_STRINGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RunService runs;

    public RunQueries(JdbcClient jdbc, ObjectMapper json, RunService runs) {
        this.jdbc = jdbc;
        this.json = json;
        this.runs = runs;
    }

    private record RunRow(
            UUID id,
            String scenario,
            String mode,
            String status,
            String request,
            String branch,
            Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable String stopReason) {}

    private record NodeRow(
            String id,
            String status,
            int attempt,
            @Nullable Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable String failureReason) {}

    private record EventRow(
            long seq,
            @Nullable String nodeId,
            String type,
            @Nullable String from,
            @Nullable String to,
            Map<String, Object> payload,
            Instant at) {}

    // ---- runs ---------------------------------------------------------------------------------------

    public List<RunSummary> list() {
        return jdbc
                .sql("SELECT * FROM orchestrator.runs ORDER BY started_at DESC LIMIT 200")
                .query(this::mapRun)
                .list()
                .stream()
                .map(row -> {
                    RunSummary summary = new RunSummary(
                            row.id(),
                            row.scenario(),
                            Mode.fromValue(row.mode()),
                            RunState.fromValue(row.status()),
                            row.request(),
                            utc(row.startedAt()));
                    summary.setFinishedAt(utc(row.finishedAt()));
                    summary.setStopReason(row.stopReason());
                    return summary;
                })
                .toList();
    }

    public RunDetail detail(UUID id) {
        return runs.find(id).map(this::live).orElseGet(() -> stored(id));
    }

    private RunDetail live(Run run) {
        List<NodeView> nodes = new ArrayList<>();
        Map<String, Run.Waiting> waiting = new HashMap<>();
        run.waiting().forEach(w -> waiting.put(w.nodeId(), w));
        for (String nodeId : run.nodeIds()) {
            StageSpec stage = run.stage(nodeId);
            NodeView view = new NodeView(
                    nodeId,
                    stage.agent(),
                    stage.dependsOn(),
                    NodeState.fromValue(run.nodeStatus(nodeId).name()),
                    run.attempt(nodeId));
            view.setFailureReason(run.failureReason(nodeId));
            if (waiting.containsKey(nodeId)) {
                view.setWaiting(pending(waiting.get(nodeId)));
            }
            nodes.add(view);
        }
        RunDetail detail = new RunDetail(
                run.id(),
                run.request().scenario(),
                Mode.fromValue(run.mode().name()),
                RunState.fromValue(run.status().name()),
                run.request().request(),
                utc(run.startedAt()),
                run.branch(),
                nodes);
        detail.setFinishedAt(utc(run.finishedAt()));
        detail.setStopReason(run.stopReason());
        return detail;
    }

    private RunDetail stored(UUID id) {
        RunRow row = runRow(id);
        Map<String, NodeRow> nodeRows = new HashMap<>();
        nodes(id).forEach(n -> nodeRows.put(n.id(), n));
        List<NodeView> nodes = new ArrayList<>();
        for (StageSpec stage : runs.pipeline().stages()) {
            NodeRow n = nodeRows.get(stage.id());
            NodeView view = new NodeView(
                    stage.id(),
                    stage.agent(),
                    stage.dependsOn(),
                    n == null ? NodeState.PENDING : NodeState.fromValue(n.status()),
                    n == null ? 0 : n.attempt());
            if (n != null) {
                view.setFailureReason(n.failureReason());
            }
            nodes.add(view);
        }
        RunDetail detail = new RunDetail(
                row.id(),
                row.scenario(),
                Mode.fromValue(row.mode()),
                RunState.fromValue(row.status()),
                row.request(),
                utc(row.startedAt()),
                row.branch(),
                nodes);
        detail.setFinishedAt(utc(row.finishedAt()));
        detail.setStopReason(row.stopReason());
        return detail;
    }

    public List<PendingApproval> approvals(UUID id) {
        return runs.find(id)
                .map(run -> run.waiting().stream().map(RunQueries::pending).toList())
                .orElseGet(() -> {
                    runRow(id); // 404 for unknown runs
                    return List.of();
                });
    }

    static PendingApproval pending(Run.Waiting w) {
        PendingApproval approval = new PendingApproval(
                w.nodeId(),
                WaitKind.fromValue(w.kind().name()),
                w.reasons(),
                w.questions().stream().map(q -> new Question(q.id(), q.text())).toList());
        approval.setSince(utc(w.since()));
        return approval;
    }

    // ---- audit ---------------------------------------------------------------------------------------

    public List<AuditEvent> events(UUID id) {
        runRow(id);
        return eventRows(id).stream()
                .map(e -> {
                    AuditEvent event = new AuditEvent(e.seq(), e.type(), e.payload(), utc(e.at()));
                    event.setNodeId(e.nodeId());
                    event.setFrom(e.from());
                    event.setTo(e.to());
                    return event;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    public RunReport report(UUID id) {
        RunRow row = runRow(id);
        List<EventRow> events = eventRows(id);
        List<String> order =
                runs.pipeline().stages().stream().map(StageSpec::id).toList();

        List<NodeReport> nodes = nodes(id).stream()
                .sorted(Comparator.comparingInt(n -> order.indexOf(n.id())))
                .map(n -> {
                    NodeReport report = new NodeReport(n.id(), NodeState.fromValue(n.status()), n.attempt());
                    if (n.startedAt() != null && n.finishedAt() != null) {
                        report.setDurationMs(
                                Duration.between(n.startedAt(), n.finishedAt()).toMillis());
                    }
                    report.setFailureReason(n.failureReason());
                    return report;
                })
                .toList();

        List<GateReport> gates = new ArrayList<>();
        List<ApprovalReport> approvals = new ArrayList<>();
        List<RollbackReport> rollbacks = new ArrayList<>();
        List<ClarificationReport> clarifications = new ArrayList<>();
        int retries = 0;
        long input = 0;
        long output = 0;
        long budget = 0;
        for (EventRow e : events) {
            Map<String, Object> p = e.payload();
            switch (e.type()) {
                case "RUN_STARTED" -> budget = number(p.get("tokenBudget"));
                case "GATE_EVALUATED" -> {
                    GateReport gate = new GateReport(
                            e.nodeId(),
                            (String) p.get("gate"),
                            (String) p.get("phase"),
                            GateReport.OutcomeEnum.fromValue((String) p.get("outcome")));
                    gate.setReason((String) p.get("reason"));
                    gate.setAttempt((int) number(p.get("attempt")));
                    gates.add(gate);
                }
                case "APPROVAL_DECIDED" -> {
                    ApprovalReport approval = new ApprovalReport(
                            e.nodeId(), ApprovalReport.DecisionEnum.fromValue((String) p.get("decision")), (String)
                                    p.get("by"));
                    approval.setKind(WaitKind.fromValue((String) p.get("kind")));
                    approval.setReason((String) p.get("reason"));
                    approval.setWaitMs(number(p.get("waitMs")));
                    approvals.add(approval);
                }
                case "NODE_RETRY" -> retries++;
                case "ROLLBACK" -> {
                    RollbackReport rollback = new RollbackReport(e.nodeId(), (String) p.get("checkpoint"));
                    rollback.setReason((String) p.get("reason"));
                    rollbacks.add(rollback);
                }
                case "CLARIFICATION_ANSWERED", "ASSUMPTION_RECORDED" -> {
                    ClarificationReport c =
                            new ClarificationReport((String) p.get("questionId"), (String) p.get("question"));
                    c.setAnswer((String) p.get("answer"));
                    c.setAssumption((String) p.get("assumption"));
                    clarifications.add(c);
                }
                case "LLM_USAGE" -> {
                    input += number(p.get("inputTokens"));
                    output += number(p.get("outputTokens"));
                }
                default -> {
                    // not summarised
                }
            }
        }

        List<ArtifactReport> artifacts = jdbc.sql("SELECT node_id, name, content_hash, parent_hashes::text AS parents"
                        + " FROM orchestrator.artifacts WHERE run_id = ? ORDER BY id")
                .param(id)
                .query((rs, n) -> new ArtifactReport(
                        rs.getString("node_id"),
                        rs.getString("name"),
                        rs.getString("content_hash"),
                        parse(rs.getString("parents"), JSON_STRINGS)))
                .list();

        RunReport report = new RunReport(
                row.id(),
                row.scenario(),
                Mode.fromValue(row.mode()),
                RunState.fromValue(row.status()),
                utc(row.startedAt()),
                nodes,
                gates,
                approvals,
                retries,
                rollbacks,
                clarifications,
                artifacts,
                new TokenReport(input, output, budget));
        report.setRequest(row.request());
        report.setBranch(row.branch());
        report.setFinishedAt(utc(row.finishedAt()));
        report.setStopReason(row.stopReason());
        if (row.finishedAt() != null) {
            report.setDurationMs(
                    Duration.between(row.startedAt(), row.finishedAt()).toMillis());
        }
        return report;
    }

    // ---- rows ---------------------------------------------------------------------------------------

    private RunRow runRow(UUID id) {
        return jdbc.sql("SELECT * FROM orchestrator.runs WHERE id = ?")
                .param(id)
                .query(this::mapRun)
                .optional()
                .orElseThrow(() -> new RunNotFoundException(id));
    }

    private RunRow mapRun(ResultSet rs, int row) throws SQLException {
        return new RunRow(
                rs.getObject("id", UUID.class),
                rs.getString("scenario"),
                rs.getString("mode"),
                rs.getString("status"),
                rs.getString("request"),
                rs.getString("branch"),
                instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")),
                rs.getString("stop_reason"));
    }

    private List<NodeRow> nodes(UUID id) {
        return jdbc.sql("SELECT * FROM orchestrator.nodes WHERE run_id = ?")
                .param(id)
                .query((rs, n) -> new NodeRow(
                        rs.getString("node_id"),
                        rs.getString("status"),
                        rs.getInt("attempt"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("finished_at")),
                        rs.getString("failure_reason")))
                .list();
    }

    private List<EventRow> eventRows(UUID id) {
        return jdbc.sql("SELECT run_seq, node_id, type, from_status, to_status, payload::text AS payload, occurred_at"
                        + " FROM orchestrator.events WHERE run_id = ? ORDER BY run_seq")
                .param(id)
                .query((rs, n) -> new EventRow(
                        rs.getLong("run_seq"),
                        rs.getString("node_id"),
                        rs.getString("type"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        parse(rs.getString("payload"), JSON_OBJECT),
                        instant(rs.getTimestamp("occurred_at"))))
                .list();
    }

    private <T> T parse(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt JSON in the audit log", e);
        }
    }

    private static long number(@Nullable Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    @Nullable
    private static Instant instant(@Nullable Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @Nullable
    private static OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

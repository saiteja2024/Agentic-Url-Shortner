package io.agentic.orchestrator.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentic.orchestrator.dag.Artifact;
import io.agentic.orchestrator.dag.EventType;
import io.agentic.orchestrator.dag.NodeStatus;
import io.agentic.orchestrator.dag.RunEvent;
import io.agentic.orchestrator.dag.RunListener;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Persists a run's audit trail (ORCH-011, ORCH-012): every event is appended to
 * {@code orchestrator.events}; {@code runs} and {@code nodes} hold the latest state for queries.
 */
@Component
public class RunRecorder implements RunListener {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RunRecorder(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void onEvent(RunEvent event) {
        Timestamp at = Timestamp.from(event.at());
        switch (event.type()) {
            case RUN_STARTED ->
                jdbc.sql("INSERT INTO orchestrator.runs"
                                + " (id, pipeline_name, pipeline_version, mode, status, started_at)"
                                + " VALUES (?, ?, ?, ?, 'RUNNING', ?)")
                        .params(
                                event.runId(),
                                event.payload().get("pipeline"),
                                event.payload().get("version"),
                                event.payload().get("mode"),
                                at)
                        .update();
            case NODE_TRANSITION -> upsertNode(event, at);
            default -> {
                // other events only go to the audit log
            }
        }
        jdbc.sql("INSERT INTO orchestrator.events"
                        + " (run_id, run_seq, node_id, type, from_status, to_status, payload, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(
                        event.runId(),
                        event.seq(),
                        event.nodeId(),
                        event.type().name(),
                        name(event.from()),
                        name(event.to()),
                        toJson(event.payload()),
                        at)
                .update();
        if (event.type() == EventType.RUN_FINISHED) {
            jdbc.sql("UPDATE orchestrator.runs SET status = ?, finished_at = ? WHERE id = ?")
                    .params(event.payload().get("status"), at, event.runId())
                    .update();
        }
    }

    @Override
    public void onArtifact(Artifact artifact) {
        jdbc.sql("INSERT INTO orchestrator.artifacts (run_id, node_id, name, content, content_hash, parent_hashes)"
                        + " VALUES (?, ?, ?, to_jsonb(?::text), ?, ?::jsonb)")
                .params(
                        artifact.runId(),
                        artifact.nodeId(),
                        artifact.name(),
                        artifact.content(),
                        artifact.contentHash(),
                        toJson(artifact.parentHashes()))
                .update();
    }

    private void upsertNode(RunEvent event, Timestamp at) {
        boolean starting = event.to() == NodeStatus.RUNNING;
        boolean finished = event.to() == NodeStatus.DONE || event.to() == NodeStatus.FAILED;
        jdbc.sql("INSERT INTO orchestrator.nodes"
                        + " (run_id, node_id, status, attempt, started_at, finished_at, failure_reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (run_id, node_id) DO UPDATE SET"
                        + " status = EXCLUDED.status, attempt = EXCLUDED.attempt,"
                        + " started_at = COALESCE(EXCLUDED.started_at, nodes.started_at),"
                        + " finished_at = EXCLUDED.finished_at, failure_reason = EXCLUDED.failure_reason")
                .params(
                        event.runId(),
                        event.nodeId(),
                        event.to().name(),
                        event.payload().getOrDefault("attempt", 0),
                        starting ? at : null,
                        finished ? at : null,
                        event.payload().get("reason"))
                .update();
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit payload is not serialisable", e);
        }
    }

    private static String name(NodeStatus status) {
        return status == null ? null : status.name();
    }
}

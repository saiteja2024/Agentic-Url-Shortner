package io.agentic.orchestrator.persistence;

import static io.agentic.orchestrator.support.Stages.gate;
import static io.agentic.orchestrator.support.Stages.pipeline;
import static io.agentic.orchestrator.support.Stages.stage;
import static io.agentic.orchestrator.support.Stages.withGates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentic.common.ContentHash;
import io.agentic.orchestrator.dag.DagEngine;
import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.dag.Run;
import io.agentic.orchestrator.dag.RunMode;
import io.agentic.orchestrator.dag.RunStatus;
import io.agentic.orchestrator.gate.GateResult;
import io.agentic.orchestrator.support.TestcontainersConfiguration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunRecorderIntegrationTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    /** Stub agents and gates (the real ones are tested separately); the recorder and database are real. */
    private final DagEngine engine = new DagEngine(
            pool,
            agent -> ctx -> NodeOutput.of(Map.of(ctx.nodeId() + ".md", "output of " + ctx.nodeId())),
            (id, spec) -> ctx -> GateResult.pass(),
            Clock.systemUTC());

    @Autowired
    private RunRecorder recorder;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    private Run runSmallPipeline() throws Exception {
        Run run = engine.start(
                pipeline(
                        Map.of("lint", gate("check")),
                        stage("requirements"),
                        withGates(stage("design", "requirements"), List.of(), List.of("lint"))),
                RunMode.GREENFIELD,
                recorder);
        assertThat(run.completion().get(10, TimeUnit.SECONDS)).isEqualTo(RunStatus.SUCCEEDED);
        return run;
    }

    @Test
    @Tag("ORCH-012")
    @DisplayName("ORCH-012: runs, node statuses and ordered events are persisted")
    void persistsRunNodesAndEvents() throws Exception {
        UUID id = runSmallPipeline().id();

        assertThat(jdbc.sql("SELECT status FROM orchestrator.runs WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT node_id || ':' || status FROM orchestrator.nodes WHERE run_id = ? ORDER BY node_id")
                        .param(id)
                        .query(String.class)
                        .list())
                .containsExactly("design:DONE", "requirements:DONE");

        List<String> types = jdbc.sql("SELECT type FROM orchestrator.events WHERE run_id = ? ORDER BY seq")
                .param(id)
                .query(String.class)
                .list();
        assertThat(types.getFirst()).isEqualTo("RUN_STARTED");
        assertThat(types.getLast()).isEqualTo("RUN_FINISHED");
        assertThat(types).contains("NODE_TRANSITION", "GATE_EVALUATED", "ARTIFACT_RECORDED");
        assertThat(jdbc.sql(
                                "SELECT payload->>'gate' FROM orchestrator.events WHERE run_id = ? AND type = 'GATE_EVALUATED'")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("lint");
    }

    @Test
    @Tag("ORCH-011")
    @DisplayName("ORCH-011: artifacts are stored with content hash and parent hashes")
    void persistsArtifactLineage() throws Exception {
        Run run = runSmallPipeline();

        String parent = ContentHash.sha256Hex("output of requirements");
        Map<String, Object> design = jdbc.sql(
                        "SELECT content_hash, parent_hashes::text AS parents, content #>> '{}' AS content"
                                + " FROM orchestrator.artifacts WHERE run_id = ? AND node_id = 'design'")
                .param(run.id())
                .query()
                .singleRow();
        assertThat(design.get("content")).isEqualTo("output of design");
        assertThat(design.get("content_hash")).isEqualTo(ContentHash.sha256Hex("output of design"));
        assertThat((String) design.get("parents")).isEqualTo("[\"" + parent + "\"]");
    }

    @Test
    @Tag("ORCH-013")
    @DisplayName("ORCH-013: UPDATE, DELETE and TRUNCATE on the audit log are rejected, even for the owner")
    void eventsAreAppendOnly() throws Exception {
        Run run = runSmallPipeline();
        long before = count("SELECT count(*) FROM orchestrator.events");

        assertThatThrownBy(() -> jdbc.sql("UPDATE orchestrator.events SET type = 'TAMPERED' WHERE run_id = ?")
                        .param(run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM orchestrator.events WHERE run_id = ?")
                        .param(run.id())
                        .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE orchestrator.events").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("append-only");

        assertThat(count("SELECT count(*) FROM orchestrator.events")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM orchestrator.events WHERE type = 'TAMPERED'"))
                .isZero();
    }

    @Test
    @Tag("ORCH-013")
    @DisplayName("ORCH-013: the application role has no UPDATE, DELETE or TRUNCATE privilege on events")
    void privilegesRevoked() {
        List<String> privileges = jdbc.sql("SELECT privilege_type FROM information_schema.role_table_grants"
                        + " WHERE table_schema = 'orchestrator' AND table_name = 'events'"
                        + " AND grantee = 'orchestrator_app'")
                .query(String.class)
                .list();
        assertThat(privileges).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}

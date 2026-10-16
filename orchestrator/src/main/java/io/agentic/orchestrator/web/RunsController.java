package io.agentic.orchestrator.web;

import io.agentic.orchestrator.dag.Run;
import io.agentic.orchestrator.dag.RunMode;
import io.agentic.orchestrator.generated.api.RunsApi;
import io.agentic.orchestrator.generated.model.KillRequest;
import io.agentic.orchestrator.generated.model.ReplanRequest;
import io.agentic.orchestrator.generated.model.RunDetail;
import io.agentic.orchestrator.generated.model.RunSummary;
import io.agentic.orchestrator.generated.model.StartRunRequest;
import io.agentic.orchestrator.service.RunQueries;
import io.agentic.orchestrator.service.RunService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RunsController implements RunsApi {

    private final RunService runs;
    private final RunQueries queries;

    RunsController(RunService runs, RunQueries queries) {
        this.runs = runs;
        this.queries = queries;
    }

    @Override
    public ResponseEntity<List<RunSummary>> listRuns() {
        return ResponseEntity.ok(queries.list());
    }

    @Override
    public ResponseEntity<RunDetail> startRun(StartRunRequest request) {
        Run run = runs.start(
                request.getScenario(),
                RunMode.valueOf(request.getMode().getValue()),
                request.getRequest(),
                request.getTokenBudget() == null
                        ? null
                        : request.getTokenBudget().longValue());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/runs/" + run.id()))
                .body(queries.detail(run.id()));
    }

    @Override
    public ResponseEntity<RunDetail> getRun(UUID runId) {
        return ResponseEntity.ok(queries.detail(runId));
    }

    @Override
    public ResponseEntity<RunDetail> killRun(UUID runId, KillRequest request) {
        runs.require(runId).stop(Deciders.resolve(request.getBy()), request.getReason());
        return ResponseEntity.ok(queries.detail(runId));
    }

    @Override
    public ResponseEntity<RunDetail> replanNode(UUID runId, String nodeId, ReplanRequest request) {
        runs.require(runId).replan(nodeId, Deciders.resolve(request.getBy()), request.getInput());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queries.detail(runId));
    }
}

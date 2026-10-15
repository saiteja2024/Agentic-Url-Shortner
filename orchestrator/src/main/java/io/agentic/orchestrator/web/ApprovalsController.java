package io.agentic.orchestrator.web;

import io.agentic.orchestrator.generated.api.ApprovalsApi;
import io.agentic.orchestrator.generated.model.AnswersRequest;
import io.agentic.orchestrator.generated.model.ApproveRequest;
import io.agentic.orchestrator.generated.model.PendingApproval;
import io.agentic.orchestrator.generated.model.RejectRequest;
import io.agentic.orchestrator.generated.model.RunDetail;
import io.agentic.orchestrator.service.RunQueries;
import io.agentic.orchestrator.service.RunService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Human checkpoints (ORCH-009, ORCH-031…034, ORCH-037). */
@RestController
class ApprovalsController implements ApprovalsApi {

    private final RunService runs;
    private final RunQueries queries;

    ApprovalsController(RunService runs, RunQueries queries) {
        this.runs = runs;
        this.queries = queries;
    }

    @Override
    public ResponseEntity<List<PendingApproval>> listApprovals(UUID runId) {
        return ResponseEntity.ok(queries.approvals(runId));
    }

    @Override
    public ResponseEntity<RunDetail> approveNode(UUID runId, String nodeId, ApproveRequest request) {
        runs.require(runId).approve(nodeId, request.getBy());
        return ResponseEntity.ok(queries.detail(runId));
    }

    @Override
    public ResponseEntity<RunDetail> rejectNode(UUID runId, String nodeId, RejectRequest request) {
        runs.require(runId).reject(nodeId, request.getBy(), request.getReason());
        return ResponseEntity.ok(queries.detail(runId));
    }

    @Override
    public ResponseEntity<RunDetail> answerClarifications(UUID runId, String nodeId, AnswersRequest request) {
        runs.require(runId).answer(nodeId, request.getBy(), request.getAnswers());
        return ResponseEntity.ok(queries.detail(runId));
    }
}

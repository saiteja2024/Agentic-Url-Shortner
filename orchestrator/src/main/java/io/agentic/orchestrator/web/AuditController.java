package io.agentic.orchestrator.web;

import io.agentic.orchestrator.generated.api.AuditApi;
import io.agentic.orchestrator.generated.model.AuditEvent;
import io.agentic.orchestrator.generated.model.RunReport;
import io.agentic.orchestrator.service.RunQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Audit log and report (ORCH-042, ORCH-043). */
@RestController
class AuditController implements AuditApi {

    private final RunQueries queries;

    AuditController(RunQueries queries) {
        this.queries = queries;
    }

    @Override
    public ResponseEntity<List<AuditEvent>> listEvents(UUID runId) {
        return ResponseEntity.ok(queries.events(runId));
    }

    @Override
    public ResponseEntity<RunReport> getReport(UUID runId) {
        return ResponseEntity.ok(queries.report(runId));
    }
}

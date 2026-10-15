# Runbook

All mutating calls need the API key from `.env` (`ORCHESTRATOR_API_KEY`) in `X-API-Key`. The
console at http://localhost:8080 does the same thing through a form. Paste the key into the
header box once.

```bash
export KEY=$(grep ^ORCHESTRATOR_API_KEY= .env | cut -d= -f2-)
```

## Start a run

```bash
curl -s -X POST localhost:8080/api/v1/runs -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"scenario":"greenfield","mode":"GREENFIELD","request":"Add click analytics"}'
```

The response has the run `id`; the run's branch is `run/<id>` in `workspaces/<id>`.

## See what is waiting for a human

```bash
curl -s localhost:8080/api/v1/runs/$RUN/approvals
```

`kind` tells you what's being asked:

| kind | Meaning | Action |
|---|---|---|
| `APPROVAL` | An approval gate or a policy rule (schema, dependency, security file, merge) matched; all machine gates already passed | approve, or reject (the node's changes are rolled back) |
| `ESCALATION` | The agent failed 3 times; its changes are already rolled back | reject to fail the node, or approve to accept the node as done |
| `CLARIFICATION` | The requirement is ambiguous; `questions` lists them | answer; unanswered questions become recorded assumptions |

## Approve, reject or answer

```bash
curl -s -X POST localhost:8080/api/v1/runs/$RUN/nodes/design/approve -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"by":"your-name"}'
```

```bash
curl -s -X POST localhost:8080/api/v1/runs/$RUN/nodes/test/reject -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"by":"your-name","reason":"why"}'
```

```bash
curl -s -X POST localhost:8080/api/v1/runs/$RUN/nodes/requirements/answers -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"by":"your-name","answers":{"Q1":"yes"}}'
```

## Kill a run

```bash
curl -s -X POST localhost:8080/api/v1/runs/$RUN/kill -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"by":"your-name","reason":"wrong requirement"}'
```

No new node or attempt starts, pending approvals are cancelled, and in-flight agent calls
finish; the run ends `CANCELLED` with the reason in the report. Budgets (`token-budget`,
`time-budget` in `application.yml`) and application shutdown stop runs the same way.

## Re-plan after a requirement changes

```bash
curl -s -X POST localhost:8080/api/v1/runs/$RUN/nodes/requirements/replan -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"by":"your-name","input":"revised requirement"}'
```

Only a finished run can be re-planned. Watch for `REPLAN_COMPARED` events: `outputChanged:false`
means nothing below that node re-ran.

## Find a trace

1. Take the run id (console header or API) or, for any HTTP call, the `X-Trace-Id` response header.
2. In OpenObserve (http://localhost:5080) → **Traces**, filter with `run_id = '<id>'` (the
   `run.id` span attribute) or by trace id, and open the `run <scenario>` span. The whole run is
   one trace: `POST /api/v1/runs` → `run` → `node …` → `gate …` (one span per attempt) and
   `approval …`, tagged with attempt, outcome and decision.
3. **Logs**: filter on `runId` or `nodeId`. Every node log line carries both, plus `traceId`.

## Read the audit trail

```bash
curl -s localhost:8080/api/v1/runs/$RUN/events
```

```bash
curl -s localhost:8080/api/v1/runs/$RUN/report
```

The report is rebuilt only from the audit log, so anything missing from the report is missing
from the log too. The log can't be edited: `UPDATE`, `DELETE` and `TRUNCATE` on
`orchestrator.events` are rejected by a trigger.

## When an alert fires

| Alert | First look | Typical cause |
|---|---|---|
| `OrchestratorRunSuccessRateLow` | Governance dashboard → gate failures by gate | A gate keeps failing (a broken seed build, a missing recording) |
| `OrchestratorRollbackSpike` | Console → runs waiting with `ESCALATION` | Agents can't satisfy a gate within 3 attempts; needs a human fix |
| `ServiceP95LatencyHigh` | Service Health → p95 by service, then traces in OpenObserve | Slow DB, or slow DNS for the shortener's SSRF check |

## Reset the environment

```bash
docker compose down -v
```

Removes all data volumes (Postgres, Grafana, Prometheus, OpenObserve). Run workspaces live in
`workspaces/` and can be deleted at any time once their runs are finished.

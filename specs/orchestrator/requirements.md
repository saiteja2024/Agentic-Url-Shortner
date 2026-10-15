# Orchestrator — Requirements

Pipeline definition: [`pipeline.yaml`](pipeline.yaml). Tests that verify a requirement carry
`@Tag("<ID>")` and a `@DisplayName` starting with `<ID>:`. `ORCH-001…013` cover the DAG
engine; agents, governance and metrics add requirements from `ORCH-020` up.

Terms:
- **Run**: one execution of a pipeline for one request.
- **Node**: one stage of a run. Its status is one of `PENDING`, `RUNNING`, `WAITING_APPROVAL`,
  `DONE`, `FAILED`, `STALE`.
- **Gate**: a check evaluated before (entry) or after (exit) a node runs. Its outcome is
  `PASS`, `FAIL` or `NEEDS_APPROVAL`.

---

## Pipeline definition

### ORCH-001 — Load the pipeline from YAML
Status: Implemented

- **Given** `specs/orchestrator/pipeline.yaml`
- **When** the orchestrator starts
- **Then** it loads the stages, their `dependsOn` edges, gates and approval policy from that file. No stage is hard-coded.
- **And** unknown keys, a missing stage `id` or `agent`, or an unreadable file fail startup with a message naming the problem

### ORCH-002 — Validate the graph before any run
Status: Implemented

- **Given** a pipeline where a stage depends on an unknown stage, two stages share an id, a stage references an undeclared gate, or an approval gate is used as an entry gate
- **When** it is loaded
- **Then** loading fails with every such problem listed, and nothing runs

### ORCH-003 — Detect cycles
Status: Implemented

- **Given** a pipeline whose dependencies form a cycle (e.g. `design → implement → design`)
- **When** it is loaded
- **Then** loading fails and the message names the stages on the cycle

## Scheduling

### ORCH-004 — Dependency order
Status: Implemented

- **Given** a valid pipeline
- **When** a run executes
- **Then** a node starts only after **every** node it depends on is `DONE`
- **And** the plan's topological order (Kahn's algorithm) is deterministic: ready nodes are taken in declaration order

### ORCH-005 — Parallel branches with a join barrier
Status: Implemented

- **Given** two nodes whose dependencies are all `DONE` (e.g. `implement` and `test-plan` after `design`)
- **When** the run executes
- **Then** they run concurrently
- **And** a node depending on both (`test`) starts only after both finish (join barrier)

### ORCH-006 — Node status transitions
Status: Implemented

- **Given** a node
- **Then** its status only moves along: `PENDING → RUNNING`, `PENDING → DONE` (skipped conditional stage, ORCH-007),
  `RUNNING → DONE | FAILED | WAITING_APPROVAL`, `WAITING_APPROVAL → DONE | FAILED | RUNNING` (re-execution after
  clarification answers, ORCH-034), `DONE → STALE`, `STALE → RUNNING`
- **And** any other transition is rejected as a programming error and leaves the status unchanged

### ORCH-007 — Conditional stages
Status: Implemented

- **Given** a stage with `when: brownfield` and a greenfield run
- **When** the run reaches it
- **Then** the stage is marked `DONE` without executing, a `NODE_SKIPPED` event records why, and its dependents proceed

## Gates and approvals

### ORCH-008 — Entry and exit gates
Status: Implemented

- **Given** a node with entry and exit gates
- **When** it runs
- **Then** entry gates are evaluated before the node executes, and exit gates after it
- **And** a `FAIL` outcome marks the node `FAILED` with the gate's reason, and its dependents never start
- **And** approval gates are exit-only: all of a node's other exit gates are evaluated first, and an approval is requested only if they all pass. An approval gate listed as an entry gate is a definition error (ORCH-002)

### ORCH-009 — Pause for approval and resume
Status: Implemented

- **Given** an exit gate that returns `NEEDS_APPROVAL`
- **When** the node finishes executing
- **Then** the node is `WAITING_APPROVAL`, its dependents wait, and independent branches keep running
- **When** a human approves
- **Then** the node becomes `DONE` and its dependents are scheduled
- **When** a human rejects
- **Then** the node becomes `FAILED` and its dependents never start

### ORCH-010 — Failure stops the affected path only
Status: Implemented

- **Given** a node that fails (a thrown exception or a failing gate)
- **Then** none of its transitive dependents start, nodes already running finish normally, and the run ends `FAILED`
- **And** the run ends `SUCCEEDED` only when every node is `DONE`

## Lineage and audit

### ORCH-011 — Content-hashed artifacts with lineage
Status: Implemented

- **Given** a node that produces artifacts
- **Then** each artifact is stored with the SHA-256 `content_hash` of its content and `parent_hashes`: the hashes of every artifact produced by the node's direct dependencies
- **And** identical content always yields the same hash

### ORCH-012 — Every state change is an audit event
Status: Implemented

- **Given** a run
- **Then** run start and end, each node transition, each gate result and each approval decision is appended to `orchestrator.events` with run id, node id, from/to status, timestamp and a JSON payload, in a strictly increasing sequence

### ORCH-013 — The audit log is append-only
Status: Implemented

- **Given** rows in `orchestrator.events`
- **When** anything attempts `UPDATE`, `DELETE` or `TRUNCATE`
- **Then** the database rejects it: privileges are revoked, and a trigger blocks even the table owner

## Agents and workspace

### ORCH-020 — LLM client with a deterministic mock
Status: Implemented

- **Given** `orchestrator.llm.mode=mock` (default)
- **Then** every agent call goes through `LlmClient`, served by `MockLlmClient` from recorded responses keyed by scenario, agent and attempt; a missing recording fails the node with a message naming the key
- **Given** `orchestrator.llm.mode=claude` and an API key
- **Then** `ClaudeLlmClient` calls the Anthropic Messages API with the same prompts
- **And** every call records prompt hash, response hash and token usage on the node's audit trail

### ORCH-021 — Eight SDLC agents
Status: Implemented

- **Given** a run
- **Then** the stages are served by Requirements, ImpactAnalysis (brownfield only), Design, Implement, TestPlan, Test, Docs and Release agents
- **And** each agent's prompt includes its direct dependencies' artifacts, so context and decisions flow down the graph
- **And** ImpactAnalysis lists impacted files that exist in the workspace; a file it names that does not exist fails the node

### ORCH-022 — One git branch per run
Status: Implemented

- **Given** a new run
- **Then** the target project is copied into `workspaces/<runId>`, initialised as a git repository on `main`, and the run works on branch `run/<runId>`
- **And** each successful node commits its changes, so every node has a checkpoint to roll back to

## Governance

### ORCH-030 — Exit gates: compile, tests, coverage, allowed paths, secrets
Status: Implemented

- **Given** the pipeline's gates
- **Then** `command` gates run in the workspace with a timeout and fail with the tail of the output; `coverage` reads the JaCoCo report and fails below `minLine`; `allowed-paths` fails if the node changed files outside its `allowedPaths`; `secret-scan` fails if added lines contain credentials or private keys

### ORCH-031 — Policy rules require approval
Status: Implemented

- **Given** the pipeline's `approvalPolicy`
- **When** a node changes a matching path (schema, dependencies, security files) or performs a matching action (`merge`)
- **Then** the node waits for human approval, and the approval request names the rules that matched

### ORCH-032 — Bounded retries with feedback, then escalation
Status: Implemented

- **Given** a node whose agent throws or whose exit gate fails
- **Then** the workspace is reset to the node's checkpoint and the node is retried with the failure text added to the prompt, up to `defaults.retries.maxAttempts` (3) attempts in total
- **When** all attempts fail
- **Then** the node is escalated to a human (`WAITING_APPROVAL`); approving accepts it as `DONE`, rejecting fails it

### ORCH-033 — Rollback
Status: Implemented

- **Given** a node whose attempts are exhausted, or a human rejection
- **Then** the run branch is reset to the node's checkpoint (no partial changes remain) and a `ROLLBACK` event records the checkpoint commit

### ORCH-034 — Clarifying questions
Status: Implemented

- **Given** a requirements output with open questions
- **Then** the node pauses (`WAITING_APPROVAL`, kind `clarification`) listing the questions
- **When** a human answers
- **Then** the answers are recorded as an artifact, the node re-executes with them, and any question left unanswered is recorded as an explicit assumption

### ORCH-035 — Safe stop: kill switch, budgets, graceful shutdown
Status: Implemented

- **Given** a running run
- **When** a human calls the kill switch, the run exceeds its time budget, or its LLM token budget is exhausted
- **Then** no new node or attempt starts, waiting approvals are cancelled, and the run ends `CANCELLED` with the reason recorded
- **And** on application shutdown every active run is stopped the same way before the process exits

### ORCH-036 — Re-plan on upstream change
Status: Implemented

- **Given** a finished run
- **When** a node is re-executed with new input and its output hash changes
- **Then** its direct dependents are marked `STALE` and re-executed, recursively; a node whose inputs' hashes did not change is not re-executed
- **And** if the re-executed node's output hash is unchanged, nothing downstream re-runs

### ORCH-037 — Approval API protected by an API key
Status: Implemented

- **Given** the HTTP API (`openapi.yaml`)
- **Then** `GET /api/v1/runs/{id}/approvals` lists waiting nodes with their reasons or questions, and `POST …/approve`, `…/reject`, `…/answers` decide them
- **And** every mutating endpoint requires `X-API-Key`; a missing or wrong key is `401` ProblemDetail and changes nothing

## Metrics, tracing and reporting

### ORCH-040 — Reliability metrics
Status: Implemented

- **Given** runs executing
- **Then** Prometheus exposes: runs by status, run duration, node duration, retries, rollbacks, gate failures by gate, approval wait time, and time-to-recover (failure → success of the same node, whose mean is MTTR)

### ORCH-041 — Trace structure
Status: Implemented

- **Given** a run
- **Then** it produces one trace: a `run` span with child `node` spans, each with child `gate` and `approval` spans, tagged with node id, attempt and result

### ORCH-042 — Run report
Status: Implemented

- **Given** a run
- **When** `GET /api/v1/runs/{id}/report` is called
- **Then** it returns the run's status, duration, per-node status/attempts/duration, gate results, approvals (who, decision, wait), retries, rollbacks, clarifications and assumptions, artifacts with hashes, and token usage

### ORCH-043 — Audit log over HTTP
Status: Implemented

- **Given** a run
- **When** `GET /api/v1/runs/{id}/events` is called
- **Then** it returns the run's events from `orchestrator.events` in sequence order

## UI

### ORCH-050 — Operator console
Status: Implemented

- **Given** the orchestrator at `http://localhost:8080/`
- **Then** a single page lists runs, starts a run, and shows the selected run's DAG with live node statuses (refreshed every 2 s)
- **And** it lets a human approve, reject, answer clarifying questions, kill the run and re-plan a node — all through the public API with the operator's API key
- **And** it shows the run's audit log and report, with links to Grafana, OpenObserve, Prometheus and Swagger UI

---

## Data model

Postgres schema `orchestrator`, owned by Flyway. Schema changes require human approval.

| Table | Purpose | Key columns |
|---|---|---|
| `runs` | One row per run | `id uuid` PK, `pipeline_name`, `pipeline_version`, `mode` (greenfield/brownfield), `status`, `started_at`, `finished_at` |
| `nodes` | Latest status per node of a run | PK (`run_id`, `node_id`), `status`, `attempt`, `started_at`, `finished_at`, `failure_reason` |
| `artifacts` | Node outputs with lineage | `id` PK, `run_id`, `node_id`, `name`, `content jsonb`, `content_hash char(64)`, `parent_hashes jsonb` (array of hashes), `created_at` |
| `events` | Append-only audit log | `seq bigint` identity PK, `run_id`, `node_id` (null for run-level), `type`, `from_status`, `to_status`, `payload jsonb`, `occurred_at` |

## Assumptions & Open Questions

| # | Assumption | Rationale |
|---|---|---|
| A1 | Execution is in-process with a bounded thread pool; runs don't survive a restart. | Durable resume (re-hydrating runs from `nodes`/`events`) is a production next step; the schema already holds what it needs. |
| A2 | A skipped conditional stage counts as `DONE` (there is no `SKIPPED` status). | Keeps the six statuses from the task list; the `NODE_SKIPPED` event preserves the distinction for audit. |
| A3 | The target project of a run is a seed project copied per run (`scenarios/seed/linkkit`), built with the local `mvn`. | Keeps runs fast and isolated; pointing the orchestrator at a real repository is a configuration change. |
| A5 | The API key is a single shared secret (`ORCHESTRATOR_API_KEY`). | Prototype scope; production would use per-user identity (OIDC) so approvals are attributable without trusting a header. |
| A4 | In the prototype the app connects as the schema owner, so the trigger (not the revoke) is what enforces append-only. | A separate least-privilege app role is the production setup; the migration already revokes for it. |

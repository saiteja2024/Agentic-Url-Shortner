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
  `RUNNING → DONE | FAILED | WAITING_APPROVAL`, `WAITING_APPROVAL → DONE | FAILED`, `DONE → STALE`, `STALE → RUNNING`
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
| A3 | Retries, rollback, budgets and re-plan are specified with governance (`ORCH-030` up). | The engine exposes the hooks they need: attempt counter, `STALE` status, events. |
| A4 | In the prototype the app connects as the schema owner, so the trigger (not the revoke) is what enforces append-only. | A separate least-privilege app role is the production setup; the migration already revokes for it. |

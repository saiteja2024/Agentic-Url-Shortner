# Testing, risks and limitations

## Testing approach

Every requirement in `specs/` has an ID, and every test that verifies one carries the ID in its
`@Tag` and `@DisplayName`. CI fails if an implemented requirement has no passing test
([traceability matrix](traceability.md): 52 requirements, about 260 test cases).

| Layer | What it proves | Examples |
|---|---|---|
| Unit | Pure logic, edge cases | Kahn order and cycle paths; the node state machine; IP-literal parsing (decimal, octal, hex, IPv4-mapped); gate implementations against a real temporary git repo |
| Engine | Governance behaviour, deterministically | Retries with feedback from a clean checkpoint, rollback, escalation, policy approvals, clarifications, kill switch, token and time budgets, minimal re-plan, metric and span structure (`GovernanceTest`, using an in-memory workspace) |
| Contract | The code matches the hand-written OpenAPI | Controllers implement generated interfaces (drift is a compile error); MockMvc responses are validated against the spec |
| Integration | Real infrastructure | Testcontainers Postgres (migrations, unique-constraint races, append-only trigger, privileges), real git, real `mvn` gates |
| End to end | The three scenarios over HTTP | `OrchestratorApiIntegrationTest` drives greenfield, brownfield and ambiguous runs to completion and checks the report and audit log |
| Concurrency | Races are decided correctly | 50 parallel creates and a same-alias race (exactly one 201), parallel DAG branches that must meet at a barrier |

Two extra checks guard against tests that can't fail: I planted bugs (removed
`Cache-Control: no-store`, broke octal parsing) and confirmed the suite caught them, and the
concurrency-heavy engine suite was run 8 times in a row without a flake.

**Coverage gate**: at least 80 % line and 70 % branch per module. Currently about 92 % line
for the shortener and 90 % for the orchestrator.

## Risks and trade-offs

| Risk / trade-off | Mitigation in this prototype | Residual |
|---|---|---|
| An agent changes files outside its stage | `allowed-paths` gate on the git diff; path traversal refused when writing | A malicious build script could still act at gate time (no sandbox) |
| Secrets committed by an agent | `secret-scan` gate on added lines (never echoes the value) | Pattern-based; a determined leak can evade regexes |
| Runaway cost or time | Token and time budgets stop the run; 3-attempt cap per node; command timeout | Budgets are per run, not per tenant |
| Approving unvetted output | Machine gates run before any approval request | Approval quality depends on the human |
| Audit tampering | Append-only trigger plus a least-privilege role; report rebuilt from the log | The DB owner can drop the trigger (that change itself needs approval) |
| Shared API key | Constant-time compare, fails closed when unset | Not per-user identity; `by` is self-declared |
| OpenAPI 3.1 generator is beta | Schema mappings and inline enums; builds verified from two path styles | New array-of-`$ref` fields need a mapping (CI catches it at compile time) |
| Boot 3.5 has left OSS support | Patched transitive versions plus expiring OSV exceptions (ADR-004) | Needs the Boot 4 migration before production |
| Mock LLM replays fixed answers | Makes scenarios reproducible and free | Feedback changes the prompt but not the recorded reply; real-model behaviour isn't exercised in CI |

## Assumptions

- The target project builds with the local `mvn` in about 3 s. Runs work on a copied seed
  project, not on this repository.
- A conditional stage that doesn't apply is `DONE` (with a `NODE_SKIPPED` event), because the
  task's status list has no `SKIPPED`.
- Runs live in memory; the database holds enough to rebuild them, but a restart cancels
  in-flight runs.
- Shortener assumptions A1–A7 and open questions Q1–Q6 are in
  [`specs/shortener/requirements.md`](../specs/shortener/requirements.md).

## Known limitations

- **`ClaudeLlmClient` is not exercised by automated tests**: the mock is used everywhere so CI
  stays deterministic and free. The real path is a configuration switch (`LLM_MODE=claude`).
- Gates run on the host. There's no container sandbox per run, and the agent's code runs with
  the orchestrator's permissions at test time.
- A single instance with in-memory run state; no horizontal scaling and no resume after restart.
- Approvals are attributed to a self-declared name behind a shared key.
- Run-level p95 needs more traffic than a demo produces; dashboards show node p95 and average
  run duration instead.
- After a re-plan, a run counts as completed twice in `orchestrator_runs_completed_total`.

## Next steps for production

1. Migrate to Spring Boot 4 / Framework 7 and drop the CVE exceptions (ADR-004).
2. Sandbox each run (container per workspace, no network, CPU and memory limits) and run gates inside it.
3. Real identity: OIDC for operators, signed approvals, per-team policies and budgets.
4. Durable execution: persist run state and resume after restart (the schema already has it), or move scheduling onto a workflow engine.
5. Point workspaces at real repositories and open pull requests instead of merging locally.
6. Evaluate real-model agents against a recorded eval set before switching the default off mock.
7. Separate the shortener and orchestrator into their own services and release pipelines.

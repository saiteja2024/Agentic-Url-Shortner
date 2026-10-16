# Recording script (8–10 minutes)

Before recording: `docker compose down -v` (clean volumes), then the three commands in the
README. Start both apps, open the console, Grafana, OpenObserve and Prometheus in tabs, and put
the API key in the console header. Do one dry run first.

| Time | Show | Say |
|---|---|---|
| 0:00–0:45 | README, `specs/` folder | The problem: turn a requirement into a reviewable outcome under governance. Specs are the source of truth: requirements with IDs, OpenAPI contracts, and the pipeline as YAML. |
| 0:45–1:30 | `docker compose ps` (all healthy), `./mvnw verify` summary, CI green on GitHub | Setup in three commands; gates: lint, contract codegen, about 225 tests, coverage, traceability, dependency scan. |
| 1:30–3:30 | Console: start **greenfield** | The DAG: requirements gate on REQ IDs, the design approval (human checkpoint), then `implement` and `test-plan` in parallel and the join barrier at `test`. Real `mvn` compile/test/coverage gates. The release asks for a merge, which policy makes a human decision. Approve both. Report: gates, approvals, artifacts with hashes. |
| 3:30–5:15 | Console: **brownfield** | Impact analysis names real files (verified). Implement attempt 1 breaks an existing test; the gate output is fed back and attempt 2 passes (MTTR recorded). The test agent fails 3 times, gets rolled back to its checkpoint and escalated; reject it. Audit log: `NODE_RETRY`, `ROLLBACK`, `APPROVAL_DECIDED`. |
| 5:15–6:45 | Console: **ambiguous** | The requirements agent asks 3 questions; answer one, and the other two become recorded assumptions. Approve through. Then re-plan with a revised requirement: requirements changes, design re-runs, its output hash is unchanged, so nothing below it re-runs. |
| 6:45–7:30 | OpenObserve → Traces, filter `run_id = '<id>'` | One trace per run: run → node → gate / approval with attempt and outcome. Logs with `runId` / `nodeId`. |
| 7:30–8:15 | Grafana: Orchestrator Overview, Governance; Prometheus → Alerts | Success rate, MTTR, node p95, retries, rollbacks, gate failures, approval waits; four alert rules (including the LLM circuit breaker). |
| 8:15–8:45 | Start a run, then **Kill switch** | Pending approvals are cancelled, nothing new starts, the run is `CANCELLED` with a reason. Budgets and shutdown use the same path. |
| 8:45–9:45 | `docs/testing-and-risks.md` | Limitations, stated plainly: mock LLM in CI, no sandbox yet, shared API key, Boot 3.5 → 4. Next steps for production. |

After recording, upload the video as unlisted and paste the link at the top of the README.

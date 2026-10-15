# Scenario: brownfield

- Run `43fcc4fc-7a74-4cd7-b663-43912fd2d1c0` on branch `run/43fcc4fc-7a74-4cd7-b663-43912fd2d1c0` — **FAILED** in 17.4 s
- Request: Links must support an optional expiry; an expired link must no longer resolve.
- Retries: 3 · Rollbacks: 1 · LLM tokens: 5328 of 200000
- Audit events: 48 (append-only, sequence 1–48)

## Nodes

| Node | Status | Attempts | Failure |
|---|---|---|---|
| requirements | DONE | 1 |  |
| impact-analysis | DONE | 1 |  |
| design | DONE | 1 |  |
| implement | DONE | 2 |  |
| test-plan | DONE | 1 |  |
| test | FAILED | 3 | rejected by sai: ExpiryTest asserts the wrong behaviour for REQ-002; needs a human fix |

## Gates

| Node | Gate | Attempt | Outcome |
|---|---|---|---|
| requirements | requirements-ids | 1 | PASS |
| design | human-approval | 1 | NEEDS_APPROVAL |
| implement | compile | 1 | PASS |
| implement | tests-pass | 1 | FAIL |
| implement | compile | 2 | PASS |
| implement | tests-pass | 2 | PASS |
| implement | allowed-paths | 2 | PASS |
| implement | secret-scan | 2 | PASS |
| test | tests-pass | 1 | FAIL |
| test | tests-pass | 2 | FAIL |
| test | tests-pass | 3 | FAIL |

## Human decisions

| Node | Kind | Decision | By | Waited |
|---|---|---|---|---|
| design | APPROVAL | APPROVED | sai | 0.7 s |
| test | ESCALATION | REJECTED | sai | 0.7 s |

## Rollbacks

- `test` reset to `cfa424019f01`: retries exhausted after 3 attempt(s): exit gate 'tests-pass' failed: 'mvn -B -q test' exit 1:
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionF

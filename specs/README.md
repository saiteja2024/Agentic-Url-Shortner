# Specs — the source of truth

Everything in this folder is written **before** the code it describes. Code, tests and
docs are derived from these files and are validated against them in CI. When a spec and
the code disagree, the code is wrong.

| File | What it defines | How it's enforced |
|---|---|---|
| `shortener/requirements.md` | Behaviour of the URL shortener as numbered requirements (`SHORT-001`…) with Given/When/Then acceptance criteria | Every requirement must be covered by a passing test tagged with its ID (`scripts/traceability.py` → `docs/traceability.md`) |
| `shortener/openapi.yaml` | The HTTP contract (OpenAPI 3.1, RFC 9457 errors) | Linted by Redocly; Java interfaces + models are **generated** from it, so controller drift is a compile error; MockMvc tests validate real responses against it |
| `orchestrator/pipeline.yaml` | The SDLC stages, their dependencies and their gates | Loaded by the orchestrator's `DagEngine` instead of hard-coding stages |

## Workflow for every change

1. **Spec first.** Add or change requirements (new ID, or edit the existing one) and the
   OpenAPI contract. IDs are never reused or renumbered; retired requirements are marked
   `Status: Retired`.
2. **Tests next.** Write tests tagged with the requirement ID:
   ```java
   @Test
   @Tag("SHORT-012")
   @DisplayName("SHORT-012: rejects private IPv4 ranges")
   void rejectsPrivateIps() { ... }
   ```
   The `@DisplayName` prefix is what the traceability check reads from the Surefire
   reports, so only tests that actually ran and passed count as coverage.
3. **Code last.** Implement the generated interface until the tests pass.

## Requirement status

| Status | Meaning | Traceability check |
|---|---|---|
| `Planned` | Agreed, not yet implemented | Listed as pending; does not fail the build |
| `Implemented` | Shipped | **Fails the build** if no passing test carries its ID |
| `Retired` | No longer applies | Ignored |

Flipping a requirement to `Implemented` is part of the feature's definition of done.

## Change control

Specs are high-impact artifacts. In the orchestrator, a spec's SHA-256 is its
`content_hash`; a change marks every downstream node `STALE` and re-plans them, and any
change to `openapi.yaml` or a database schema requires human approval. See
`DECISIONS.md` (ADR-005).

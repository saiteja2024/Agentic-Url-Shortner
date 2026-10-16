# Decisions

Architecture Decision Records, newest last. Each records the context, the decision, and
what it costs us. Superseded ADRs are kept and marked, never deleted.

## Working agreement — spec → tests → code

Every feature task starts by updating its spec (`specs/**/requirements.md` and, for API
changes, `openapi.yaml`), then the tests tagged with the requirement IDs, then the code.
A requirement flips from `Planned` to `Implemented` in the same change that ships it.
See ADR-005 and [`specs/README.md`](specs/README.md).

---

## ADR-001 — Maven multi-module monorepo

**Status:** Accepted · 2026-10-15

**Context.** The deliverable has two runtime services, the URL shortener (the system
the agents work on) and the orchestrator (the agentic layer), plus a few shared
concerns. Reviewers need to clone the repo and run everything locally with little setup.

**Decision.** One Git repo, one Maven reactor (Java 21, Spring Boot 3.5):

| Module | Kind | Port |
|---|---|---|
| `common` | Plain library: shared utilities with no Spring dependency (content hashing, MDC keys) | — |
| `shortener` | Spring Boot service | 8081 |
| `orchestrator` | Spring Boot service | 8080 |

Top-level folders: `specs/` (source of truth), `ui/`, `scenarios/` (saved run outputs),
`observability/` (Prometheus and Grafana config), `docs/`, `scripts/`.

**Consequences.**
- One `./mvnw verify` builds, checks and tests everything, and versions can't drift between services.
- `common` must stay small. If domain logic lands there, the two services become coupled.
- In production these would likely be separate repos with separate release cycles.

## ADR-002 — Local infrastructure via Docker Compose, apps on the host

**Status:** Accepted · 2026-10-15

**Context.** The prototype must run on Docker Desktop with metrics, traces and logs.

**Decision.**
- `docker-compose.yml` runs Postgres 16, OpenObserve (traces and logs, OTLP), Prometheus
  and Grafana, each pinned to an exact image tag and each with a healthcheck, so
  `docker compose up -d --wait` blocks until the stack is actually usable.
- The apps run on the host (`./mvnw spring-boot:run`) for a fast edit-and-run loop, and
  Prometheus scrapes them through `host.docker.internal`.
- OpenObserve ships a distroless image with no shell or HTTP client. A one-shot
  `busybox:musl` (statically linked) init container copies a binary into a shared volume,
  and the healthcheck runs `busybox wget /healthz`. This gives a real HTTP check rather
  than just "the process is up".
- Secrets come from `.env`, which is git-ignored. `.env.example` documents every
  variable, and Compose refuses to start (`${VAR:?}`) if a required secret is missing.

**Consequences.** Containerising the apps (Dockerfiles plus compose services) is a
follow-up once the services exist. The busybox workaround should be dropped if
OpenObserve adds a native health command.

## ADR-003 — Build quality gates

**Status:** Accepted · 2026-10-15

**Decision.** `./mvnw verify` fails on any of:
- **Checkstyle** (`validate` phase) with a custom ruleset covering correctness and
  hygiene only: star or unused imports, empty catch, `equals` without `hashCode`,
  `System.out`, `printStackTrace`, missing braces, and so on. Layout belongs to the
  formatter, so the ruleset doesn't fight Spotless. `@SpringBootApplication` classes are
  exempt from `HideUtilityClassConstructor`.
- **Spotless** (palantir-java-format, import order, sorted POMs). Fix it with `./mvnw spotless:apply`.
- **JaCoCo coverage gate**: at least 80 % line and 70 % branch coverage **per module**.
  Excluded: `*Application` entry points and OpenAPI-generated code (`**/generated/**`).

**Consequences.** The gate is per module rather than aggregate, so a well-tested module
can't hide an untested one. The thresholds are a starting point and will be revisited
once real code exists.

## ADR-004 — Dependency security: Boot 3.5 end of OSS support

**Status:** Accepted, with a time limit · 2026-10-15

**Context.** OSV scanning showed that Spring Boot 3.5.6 pulls in 12 known
vulnerabilities. Even the final 3.5.x release (3.5.16) still ships known CVEs, because
Boot 3.5 / Framework 6.2 left OSS support in mid-2026 and fixes now land only in Spring
Framework 7 / Boot 4. The task plan and the contract-first generator settings
(`useSpringBoot3=true`) target Boot 3.

**Decision.**
1. Stay on **Boot 3.5.16** for the prototype, and override the Boot-managed versions
   that have fixes on their own release lines: Jackson 2.21.7, Tomcat 10.1.60, Log4j
   2.25.5, commons-lang3 3.18.0, and Rhino 1.7.15.1 (test scope only). The overrides are
   in the parent `pom.xml`, each with its CVE.
2. Two Spring Framework advisories have **no 6.2.x fix**: CVE-2026-47890 (SSE fragment
   rendering) and CVE-2026-47884 (`XsltView`). We use neither feature, so they're
   recorded as **expiring exceptions** in `osv-scanner.toml` (until 2027-01-15, after
   which CI fails again). A Checkstyle `IllegalImport` rule blocks `XsltView` so the
   exception stays valid.
3. CI scans with **OSV-Scanner** (the full transitive Maven graph, failing on any
   vulnerability that isn't excepted) on every push and PR, plus GitHub
   **dependency-review** on PR diffs (failing on high severity and above).

**Consequences.** Production use would require migrating to Boot 4 / Framework 7
(Jakarta EE 11, Jackson 3, springdoc 3, `useSpringBoot4`), which is tracked as the first
"next step for production". Version overrides drift and must be removed when they're no
longer needed.

## ADR-005 — Spec-driven, contract-first development

**Status:** Accepted · 2026-10-15

**Context.** The assessment centres on turning requirements into reviewable engineering
output, with traceability and governed change. The orchestrator also needs a
machine-readable, hashable definition of *what* is being built, so it can detect
upstream changes and re-plan. If the API and the behaviour exist only in code, there's
nothing stable for agents to work from, nothing to hash, and nothing for a human to
approve before implementation.

**Decision.**
- **Requirements with stable IDs.** `specs/<service>/requirements.md` lists requirements
  (`SHORT-001`…) with Given/When/Then acceptance criteria, a status
  (`Planned` / `Implemented` / `Retired`), and explicit Assumptions & Open Questions.
- **Hand-written OpenAPI 3.1** (`specs/shortener/openapi.yaml`) with RFC 9457
  ProblemDetail errors, written before any controller and linted in CI (Redocly CLI,
  pinned version).
- **Generated interfaces.** `openapi-generator-maven-plugin` (the `spring` generator,
  `interfaceOnly`, `useSpringBoot3`, `useJakartaEe`, `skipDefaultInterface`,
  `openApiNullable=false`) generates API interfaces and models into
  `target/generated-sources`. Controllers implement them, so drift from the spec becomes
  a **compile error**. Generated code is excluded from Spotless, Checkstyle and JaCoCo.
- **Runtime contract checks.** MockMvc tests use `swagger-request-validator-mockmvc` to
  assert that real requests and responses match the spec.
- **Traceability.** Every test that verifies a requirement carries
  `@Tag("SHORT-007")` and `@DisplayName("SHORT-007: …")`. `scripts/traceability.py`
  reads the Surefire reports (so only tests that ran and passed count), writes
  `docs/traceability.md`, and fails CI if an `Implemented` requirement has no passing
  test or if a test references an unknown ID.
- **Pipeline as YAML.** `specs/orchestrator/pipeline.yaml` declares the stages,
  dependencies, gates and approval policy. The DagEngine loads it instead of hard-coding
  stages.
- **Swagger UI** (springdoc) serves only the hand-written spec. springdoc's UI needs its
  api-docs endpoint, so that endpoint stays on, but it scans nothing and moves off
  `/v3/api-docs`.

**Consequences.**
- Spec files feed the orchestrator's lineage. A spec's SHA-256 is its `content_hash`, and
  when it changes, every downstream node is marked `STALE` and re-planned.
- Changes to `openapi.yaml` or a DB schema are high-impact and **trigger human approval**
  (the `approvalPolicy` in `pipeline.yaml`).
- Trade-offs: generator setup time; less flexibility for quick API tweaks, since every
  change goes spec → regenerate → implement; and generated-code conventions such as
  `ResponseEntity` return types.
- The OpenAPI 3.1 parser can't resolve file paths containing spaces, so the build passes
  the spec as a percent-encoded `file://` URI (`${specs.uri}`, computed by
  build-helper), and tests load the spec as inline content.
- The generator's OpenAPI 3.1 support is beta. On some checkout paths it dereferences
  `items: $ref` and emits duplicate `<Parent>_<prop>_inner` models. CI caught this:
  `UrlStatsTopReferrersInner` appeared there but not locally. `schemaMappings` in
  `shortener/pom.xml` map those names back to the component models, so every machine gets
  the same types. A new array-of-`$ref` needs a new mapping, and CI fails at compile time if
  one is missing.
- The same path-dependent dereferencing turned `$ref`s to shared **enum** schemas into per-class
  inline enums on CI only. The orchestrator spec therefore declares enums inline at each use, so
  every checkout generates the same per-class `…Enum` types. Before pushing a spec change, run a
  clean build from a checkout path without spaces as well.

**Rejected: code-first springdoc.** Generating the spec from annotations means the spec
trails the code. Nobody reviews the contract before it's built, breaking changes surface
only after implementation, and there's no independent artifact to hash or approve.

## ADR-006 — CI on GitHub Actions

**Status:** Accepted · 2026-10-15

**Decision.** `.github/workflows/ci.yml` runs on every push to `main` and on every PR:
`spec-lint` (Redocly), then `build` (`./mvnw verify` covering Checkstyle, codegen, tests,
Spotless and the JaCoCo gate, followed by the traceability check, with reports uploaded
as artifacts), plus `dependency-scan` (OSV-Scanner) and, on PRs only,
`dependency-review`. Third-party actions are pinned to commit SHAs. Workflow
permissions are read-only by default, and jobs widen them only where needed.

**Consequences.** SHA pins need deliberate updates (Dependabot for `github-actions` is
a follow-up).

## ADR-007 — Shortener implementation choices

**Status:** Accepted · 2026-10-15

**Context.** SHORT-001 … SHORT-017 need unique codes under concurrency, SSRF-safe targets, and
analytics, all verifiable against the contract.

**Decision.**
- **Data access with `JdbcClient` and plain SQL, not JPA.** There are two tables and five
  queries. The on-conflict insert and the top-referrers aggregation read more clearly as SQL,
  and there is no ORM session or lazy loading to reason about. Table names are
  schema-qualified (`shortener.urls`) instead of relying on `search_path`.
- **The database decides uniqueness.** Creates use
  `INSERT … ON CONFLICT (code) DO NOTHING RETURNING …`, so a collision is an empty result, not
  an exception. Generated codes retry up to 5 times; a taken alias is an immediate 409. No
  locks or pre-checks, so there is no check-then-act race; the concurrency test proves exactly
  one winner per alias.
- **Random codes** come from `SecureRandom`: 7 Base62 characters (≈3.5 × 10¹²), so codes
  can't be enumerated by walking a sequence.
- **SSRF guardrails** follow browser (WHATWG) host parsing, so `2130706433`, `0x7f000001`,
  `0177.0.0.1`, `127.1` and IPv4-mapped IPv6 are all recognised as loopback. Hostnames are
  resolved once at creation and rejected if any address is loopback, private, link-local,
  CGNAT, ULA, multicast or a cloud metadata endpoint. DNS sits behind a `HostResolver`
  interface so tests never touch real DNS.
- **Click recording is best-effort**: a storage failure is logged and counted
  (`shortener.click.record.failures`) and the redirect still succeeds. `Cache-Control: no-store`
  keeps browsers from skipping the service.
- **Strict JSON input**: unknown fields are a 400 (the contract says
  `additionalProperties: false`), and `format: uri` is generated as `String` so validation sees
  the raw input.
- **Errors** come from one `@RestControllerAdvice`. Everything is RFC 9457 with
  `type = https://agentic.local/problems/<slug>`, an `instance`, `errors[]` for field problems,
  and a scrubbed `detail` on 5xx.
- **Metrics** (Micrometer): `shortener.urls.issued{kind}`, `shortener.redirects{outcome}`,
  `shortener.code.collisions`, `shortener.code.exhausted`, `shortener.click.record.failures`.
  They're exported to Prometheus. Note that `_created` is a reserved OpenMetrics suffix:
  `shortener.urls.created` was silently exported as `shortener_urls_total`, so it was
  renamed to `shortener.urls.issued`.

**Consequences.**
- DNS is checked only at creation (assumption A3). A name that later re-points to a private
  address is not caught (open question Q3).
- The DNS lookup has no explicit timeout beyond the JVM resolver's. Production would add a
  bounded, cached resolver.
- Recording clicks synchronously adds one insert to each redirect (assumption A6). At scale
  this would move to an async buffer or a queue.
- Internationalised domain names are rejected because `java.net.URI` doesn't parse them.
  Punycode (`xn--…`) hosts work.

## ADR-008 — DAG engine: futures as the scheduler, the database as the auditor

**Status:** Accepted · 2026-10-15

**Context.** ORCH-001 … ORCH-013 need dependency ordering, parallel branches with a join
barrier, pausing for approval without blocking threads, and an audit trail nobody can rewrite.

**Decision.**
- **Stages come from `specs/orchestrator/pipeline.yaml`.** The file is copied onto the
  classpath, strictly parsed (unknown keys fail) and validated at startup. Every problem is
  reported at once, and Kahn's algorithm rejects cycles with the cycle path named.
- **Each node is a `CompletableFuture`.** It is
  `allOf(dependencies).thenComposeAsync(runNode)` on a bounded pool, which gives the join
  barrier for free. A failed node completes exceptionally, so all its transitive dependents are
  skipped automatically while unrelated branches carry on. A node waiting for approval holds
  an *incomplete* future, which pauses only its own downstream path and uses no thread while
  waiting.
- **Approval gates are exit-only** and evaluated after every other exit gate passes, so a human
  never approves output that a machine check would reject.
- **Events are serialised per run.** A sequence number is assigned and delivered under the
  run's lock, so the audit log order is the real order. A listener that throws fails the step:
  a state change that can't be recorded must not proceed.
- **Append-only audit in the database.** A trigger rejects `UPDATE`/`DELETE`/`TRUNCATE` on
  `orchestrator.events` even for the owner, and a least-privilege `orchestrator_app` role has
  only `SELECT`/`INSERT`. The trigger is what protects the prototype, which connects as the
  owner; the role is the production setup.
- **Lineage:** artifact `content_hash` is SHA-256 (`common.ContentHash`), and `parent_hashes`
  are the sorted hashes of all direct-dependency artifacts. Re-plan compares these hashes.

**Consequences.** Runs live in memory, so a restart loses in-flight runs, although the schema
holds enough to rebuild them (a production next step). The futures model makes retries a
per-node loop inside `runNode` rather than graph surgery, which keeps governance simple.

## ADR-009 — Agents, governance and the run API

**Status:** Accepted · 2026-10-15

**Decision.**
- **Mock-first LLM.** Agents call `LlmClient`. `MockLlmClient` replays recordings keyed by
  scenario, agent and attempt, so runs are deterministic, offline and free in demos and CI.
  `ClaudeLlmClient` uses the official Anthropic Java SDK (`claude-opus-5-5`, server-side refusal
  fallbacks enabled) when `LLM_MODE=claude`, and startup fails fast without an API key.
- **Agents act through files, not tools.** A reply's `<<<FILE path … >>>` blocks are written to
  the run's workspace, with path traversal refused. `## Open questions` raises clarifications
  and `ACTION: merge` requests the merge. Impact analysis must name files that exist.
- **One git branch per run** (`run/<id>` in `workspaces/<id>`). Each node is checkpointed by a
  commit; retries and rollbacks are `git reset --hard <checkpoint>`; merge into `main` happens
  only after the merge approval.
- **Real gates on a real project.** The target is a small Maven project
  (`scenarios/seed/linkkit`). `compile`, `tests-pass` and `coverage` run real `mvn` (about 3 s)
  with a timeout; `allowed-paths`, `secret-scan` and `requirements` inspect the git diff and
  the artifact. A failed gate's output is fed back into the next attempt's prompt.
- **Governance lives in the engine, not in the agents.** Retries (3 attempts), rollback,
  escalation, policy approvals, clarifications, the kill switch, token and time budgets and
  re-plan are all enforced by `Run`, so no agent can bypass them.
- **The report is rebuilt from the audit log.** `GET /runs/{id}/report` reads only
  `orchestrator.events`, `nodes` and `artifacts`, so anything missing from the audit trail is
  missing from the report too, which keeps the audit honest.
- **API key, fail closed.** Mutations need `X-API-Key` (constant-time compare); with no key
  configured, every mutation is refused. Reads stay open for the UI and dashboards.

**Consequences.** Mock recordings make the three scenarios reproducible but not intelligent:
feedback changes the prompt, not the recorded reply. The brownfield scenario's recordings are
written so that the retry visibly succeeds on attempt 2. The real-model path is exercised only
manually.

## ADR-010 — OAuth2 JWT resource server next to the API key

**Status:** Accepted · 2026-10-15

**Context.** A shared API key can't say *who* approved a design or a merge: the `by` field was
self-declared. Enterprise operators sign in through an identity provider and expect scoped,
short-lived tokens.

**Decision.**
- The orchestrator is a **Spring Security OAuth2 resource server**. Keys come from a JWK set
  (`jwk-set-uri`) or OIDC discovery (`issuer-uri`) in production, or a shared HS256 secret
  (`hmac-secret`, at least 32 bytes) for local development, minted with
  `scripts/mint_dev_jwt.py`. Expiry is always checked; issuer and audience when configured.
- **Least privilege through scopes**: `runs:write` (start, kill, re-plan) and `approvals:write`
  (approve, reject, answer). A valid token without the scope is `403`; reads stay open.
- **Identity from the token**: a decision made with a token records `preferred_username` (or
  `sub`) as the decider and ignores the body's `by`, so approvals in the audit log are attributable.
- **The API key stays** as a break-glass and automation credential with both scopes, so the
  console, scripts and recorded scenarios keep working unchanged. It still fails closed.
- Stateless (no sessions), so CSRF protection is off; 401 and 403 are RFC 9457 ProblemDetail
  with a `WWW-Authenticate: Bearer` challenge.

**Consequences.** Per-user identity is real when callers use tokens; the console still uses the
key (adding an OIDC login to the console is the next step). The API-key principal is recorded
by its self-declared `by`, as before.

## ADR-011 — Resilience4j retry and circuit breaker around the model

**Status:** Accepted · 2026-10-15

**Context.** Every agent call goes to an external model API. Rate limits and overloads are
normal; a hard outage would otherwise make every node spend its three attempts waiting on
timeouts and then escalate, flooding humans with noise.

**Decision.**
- `ResilientLlmClient` wraps every `LlmClient` (mock or Claude) in Resilience4j instances named
  `llm`: a **retry** (3 attempts, exponential backoff from 2 s) around a **circuit breaker**
  (count window of 10, opens at 50 % failures after 5 calls, 30 s open, 2 half-open trial calls).
- **Only transient failures count**: `ClaudeLlmClient` maps I/O errors, HTTP 429 and 5xx to
  `LlmUnavailableException`; bad requests, refusals and missing recordings pass through untouched,
  so they neither retry nor trip the breaker. The SDK's own retries are off, so there is one
  retry policy, not two stacked.
- When the circuit is open, calls fail fast with a clear message; the engine's node-level
  retry and escalation (ORCH-032) then apply as for any agent failure.
- Settings live in `application.yml` (`resilience4j.*`), metrics are exported through
  Micrometer (`resilience4j_circuitbreaker_*`, `resilience4j_retry_*`), Service Health shows the
  breaker state, and `LlmCircuitBreakerOpen` alerts after one minute open.

**Consequences.** Model outages surface in a minute as one alert instead of many escalations.
The breaker is per instance, not shared across replicas.

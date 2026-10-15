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

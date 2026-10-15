# Agentic SDLC Orchestrator

An agentic orchestration layer that drives a URL shortener through a governed
software lifecycle: requirements → design → implementation → tests → docs → release,
with gates, human approvals, retries, rollback, and full audit and observability.

> Status: **Day 1.** The URL shortener is implemented against its spec (SHORT-001 … SHORT-017,
> see [`docs/traceability.md`](docs/traceability.md)). The orchestrator is next. Everything is built
> spec → tests → code from [`specs/`](specs/README.md).

## Quick start

```bash
cp .env.example .env              # then change the passwords
docker compose up -d --wait       # Postgres, OpenObserve, Prometheus, Grafana (all healthy)
./mvnw verify                     # lint, codegen, tests, coverage gate
```

| Service | URL |
|---|---|
| Orchestrator | http://localhost:8080 |
| Shortener API (+ Swagger UI at `/swagger-ui/index.html`) | http://localhost:8081 |
| Grafana | http://localhost:3000 |
| OpenObserve | http://localhost:5080 |
| Prometheus | http://localhost:9090 |
| Postgres | localhost:5432 |

## Try the shortener

```bash
./mvnw -pl shortener spring-boot:run      # needs `docker compose up -d` for Postgres
```

```bash
curl -si localhost:8081/api/v1/urls -H 'Content-Type: application/json' -d '{"url":"https://www.example.org/docs"}'
```

Then open `http://localhost:8081/<code>` to follow the redirect, and
`http://localhost:8081/api/v1/urls/<code>/stats` to see the clicks.

## Layout

| Path | Contents |
|---|---|
| `specs/` | Source of truth: requirements (with IDs), OpenAPI contract, pipeline definition |
| `common/`, `shortener/`, `orchestrator/` | Maven modules (Java 21, Spring Boot 3.5) |
| `observability/` | Prometheus and Grafana configuration |
| `scripts/` | Build tooling (`traceability.py`) |
| `docs/` | Architecture, runbook, generated `traceability.md` |
| `scenarios/` | Saved audit logs and reports from the greenfield, brownfield and ambiguous runs |
| `ui/` | Orchestrator UI |

## Checks

```bash
npx --yes @redocly/cli@2.56.1 lint      # OpenAPI lint
./mvnw verify                           # Checkstyle, codegen, tests, Spotless, JaCoCo ≥80% line / 70% branch
python3 scripts/traceability.py         # requirement → test matrix → docs/traceability.md
./mvnw spotless:apply                   # auto-format
```

Design decisions and trade-offs are in [`DECISIONS.md`](DECISIONS.md).

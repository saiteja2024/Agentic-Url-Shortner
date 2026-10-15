# Observability — Requirements

Applies to both services (shortener :8081, orchestrator :8080). Backends run in
`docker-compose.yml`: Prometheus :9090 (metrics), Grafana :3000 (dashboards), OpenObserve :5080
(traces and logs, via OTLP/HTTP).

### OBS-001 — Prometheus metrics endpoint
Status: Implemented

- **Given** a running service
- **When** Prometheus scrapes `GET /actuator/prometheus`
- **Then** it receives JVM, HTTP-server and the service's custom metrics in Prometheus text format, tagged `application=<service name>`
- **And** `observability/prometheus/prometheus.yml` scrapes both services

### OBS-002 — Distributed traces to OpenObserve
Status: Implemented

- **Given** a request to either service
- **Then** a trace is created (100 % sampling in the prototype) and exported over OTLP/HTTP to OpenObserve, with `service.name` set to the service name
- **And** the response carries the trace id in a `traceparent`-compatible form for log correlation

### OBS-003 — Structured JSON logs with correlation ids
Status: Implemented

- **Given** any log line written while handling a request or running a DAG node
- **Then** it is a single-line JSON object that includes `traceId` and `spanId` when a span is active, and `runId` and `nodeId` while a node runs
- **And** logs are also shipped to OpenObserve over OTLP

### OBS-004 — Grafana is provisioned, not hand-configured
Status: Implemented

- **Given** `docker compose up`
- **Then** Grafana has a Prometheus datasource (uid `prometheus`) and loads dashboards from `observability/grafana/dashboards` without any manual step

### OBS-005 — Three provisioned dashboards
Status: Implemented

- **Given** Grafana
- **Then** it shows **Orchestrator Overview** (success rate, active runs, run/node duration p95, MTTR, tokens), **Governance** (retries, rollbacks, gate failures, approval waits and decisions) and **Service Health** (request rate, p95 latency, 5xx ratio, shortener business metrics, JVM and DB pool) — exported as JSON in `observability/grafana/dashboards`
- **And** every query uses the Prometheus datasource and metrics the services actually expose

### OBS-006 — Alert rules
Status: Implemented

- **Given** Prometheus
- **Then** three rules are loaded from `observability/prometheus/alerts.yml`: run success rate below 80 % (over at least 3 runs in 1 h), more than 2 rollbacks in 15 min, and HTTP p95 latency above 500 ms for 5 min
- **And** each rule has a severity label and a summary annotation

## Assumptions

| # | Assumption | Rationale |
|---|---|---|
| A1 | OTLP export is best effort: if OpenObserve is down, the services keep working and drop telemetry. | Observability must not take the product down. |
| A2 | Sampling is 100 % (`management.tracing.sampling.probability: 1.0`). | Prototype traffic is tiny; production would sample at the collector. |
| A3 | OpenObserve credentials reach the services as a pre-built `Basic` header (`OTLP_AUTH_HEADER` in `.env`). | Spring properties can't base64-encode; the header is derived from the same `.env` values. |

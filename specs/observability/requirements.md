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

## Assumptions

| # | Assumption | Rationale |
|---|---|---|
| A1 | OTLP export is best effort: if OpenObserve is down, the services keep working and drop telemetry. | Observability must not take the product down. |
| A2 | Sampling is 100 % (`management.tracing.sampling.probability: 1.0`). | Prototype traffic is tiny; production would sample at the collector. |
| A3 | OpenObserve credentials reach the services as a pre-built `Basic` header (`OTLP_AUTH_HEADER` in `.env`). | Spring properties can't base64-encode; the header is derived from the same `.env` values. |

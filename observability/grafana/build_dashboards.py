#!/usr/bin/env python3
"""Generates the provisioned Grafana dashboards (OBS-005) into observability/grafana/dashboards/.

Dashboards are code: edit the panel lists below and re-run; the JSON is committed so Grafana
provisioning needs no build step. Standard library only.
"""

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards"
DS = {"type": "prometheus", "uid": "prometheus"}


def panel(kind, title, exprs, x, y, w, h, unit="short", legend="{{__name__}}", description="", **options):
    targets = [
        {"refId": chr(65 + i), "expr": e[0] if isinstance(e, tuple) else e,
         "legendFormat": e[1] if isinstance(e, tuple) else legend, "datasource": DS}
        for i, e in enumerate(exprs)
    ]
    p = {
        "type": kind, "title": title, "description": description, "datasource": DS, "targets": targets,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "palette-classic"}}, "overrides": []},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
    }
    if kind == "stat":
        p["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": "value", "graphMode": "none", "textMode": "auto"}
        p["fieldConfig"]["defaults"]["color"] = {"mode": "thresholds"}
        p["fieldConfig"]["defaults"]["thresholds"] = options.get("thresholds", {
            "mode": "absolute", "steps": [{"color": "blue", "value": None}]})
    if kind == "timeseries":
        p["fieldConfig"]["defaults"]["custom"] = {"drawStyle": options.get("draw", "line"), "lineWidth": 2,
                                                  "fillOpacity": 10, "showPoints": "auto", "spanNulls": True}
    if kind == "barchart":
        p["options"].update({"orientation": "horizontal", "showValue": "auto", "xTickLabelRotation": 0})
    return p


def dashboard(uid, title, description, panels):
    for i, p in enumerate(panels, start=1):
        p["id"] = i
    return {
        "uid": uid, "title": title, "description": description, "tags": ["agentic-sdlc"],
        "timezone": "browser", "schemaVersion": 39, "version": 1, "editable": False,
        "time": {"from": "now-1h", "to": "now"}, "refresh": "10s", "panels": panels,
    }


GOOD_BAD = {"mode": "absolute", "steps": [{"color": "red", "value": None}, {"color": "orange", "value": 0.8},
                                          {"color": "green", "value": 0.95}]}
COMPLETED = "orchestrator_runs_completed_total"

overview = dashboard("orchestrator-overview", "Orchestrator Overview",
    "Run outcomes, latency and MTTR for the agentic SDLC orchestrator.", [
    panel("stat", "Run success rate (24 h)",
          [f'sum(increase({COMPLETED}{{status="SUCCEEDED"}}[24h])) / clamp_min(sum(increase({COMPLETED}{{status=~"SUCCEEDED|FAILED"}}[24h])), 1)'],
          0, 0, 6, 5, unit="percentunit", thresholds=GOOD_BAD,
          description="Succeeded / (succeeded + failed). Cancelled runs (kill switch, budgets) are excluded."),
    panel("stat", "Active runs", ["sum(orchestrator_runs_active)"], 6, 0, 6, 5,
          description="Running or waiting for a human."),
    panel("stat", "MTTR (24 h)",
          ["sum(increase(orchestrator_recovery_seconds_sum[24h])) / clamp_min(sum(increase(orchestrator_recovery_seconds_count[24h])), 1)"],
          12, 0, 6, 5, unit="s", description="Mean time from a node's first failure to its recovery on retry."),
    panel("stat", "LLM tokens (24 h)", ["sum(increase(orchestrator_llm_tokens_total[24h]))"], 18, 0, 6, 5),
    panel("timeseries", "Runs completed by status",
          [(f"sum by (status) (increase({COMPLETED}[5m]))", "{{status}}")], 0, 5, 12, 8, draw="bars"),
    panel("timeseries", "Average run duration by scenario (includes human waits)",
          [("sum by (scenario) (orchestrator_run_seconds_sum) / sum by (scenario) (orchestrator_run_seconds_count)", "{{scenario}}")],
          12, 5, 12, 8, unit="s",
          description="Cumulative average. Run-level p95 needs more volume than a demo produces; node p95 is below."),
    panel("timeseries", "Node duration p95 by node",
          [("histogram_quantile(0.95, sum by (le, node) (rate(orchestrator_node_seconds_bucket[15m])))", "{{node}}")],
          0, 13, 12, 9, unit="s"),
    panel("timeseries", "LLM tokens by node",
          [("sum by (node) (increase(orchestrator_llm_tokens_total[5m]))", "{{node}}")], 12, 13, 12, 9, draw="bars"),
])

governance = dashboard("orchestrator-governance", "Governance",
    "Gates, retries, rollbacks and human checkpoints.", [
    panel("stat", "Retries (24 h)", ["sum(increase(orchestrator_retries_total[24h]))"], 0, 0, 6, 5),
    panel("stat", "Rollbacks (24 h)", ["sum(increase(orchestrator_rollbacks_total[24h]))"], 6, 0, 6, 5,
          thresholds={"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "orange", "value": 1},
                                                    {"color": "red", "value": 3}]}),
    panel("stat", "Gate failures (24 h)", ["sum(increase(orchestrator_gate_failures_total[24h]))"], 12, 0, 6, 5),
    panel("stat", "Avg approval wait (24 h)",
          ["sum(increase(orchestrator_approval_seconds_sum[24h])) / clamp_min(sum(increase(orchestrator_approval_seconds_count[24h])), 1)"],
          18, 0, 6, 5, unit="s"),
    panel("barchart", "Gate failures by gate (24 h)",
          [("sum by (gate) (increase(orchestrator_gate_failures_total[24h]))", "{{gate}}")], 0, 5, 12, 8),
    panel("barchart", "Human decisions by outcome (24 h)",
          [("sum by (kind, decision) (increase(orchestrator_approval_seconds_count[24h]))", "{{kind}} {{decision}}")],
          12, 5, 12, 8),
    panel("timeseries", "Retries and rollbacks by node",
          [("sum by (node) (increase(orchestrator_retries_total[5m]))", "retries {{node}}"),
           ("sum by (node) (increase(orchestrator_rollbacks_total[5m]))", "rollbacks {{node}}")],
          0, 13, 12, 9, draw="bars"),
    panel("timeseries", "Gate outcomes",
          [("sum by (gate, outcome) (increase(orchestrator_gate_seconds_count[5m]))", "{{gate}} {{outcome}}")],
          12, 13, 12, 9, draw="bars"),
])

health = dashboard("service-health", "Service Health",
    "HTTP traffic, latency and resources for the orchestrator and the shortener.", [
    panel("stat", "Services up", [('sum(up{job=~"orchestrator|shortener"})', "up")], 0, 0, 6, 5,
          thresholds={"mode": "absolute", "steps": [{"color": "red", "value": None}, {"color": "green", "value": 2}]}),
    panel("stat", "5xx ratio (5 m)",
          ['(sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) or vector(0)) / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 1e-9)'],
          6, 0, 6, 5, unit="percentunit"),
    panel("stat", "Short URLs issued (24 h)", ["sum(increase(shortener_urls_issued_total[24h]))"], 12, 0, 6, 5),
    panel("stat", "Click-record failures (24 h)", ["sum(increase(shortener_click_record_failures_total[24h]))"], 18, 0, 6, 5),
    panel("timeseries", "Request rate by service and status",
          [('sum by (application, status) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))', "{{application}} {{status}}")],
          0, 5, 12, 8, unit="reqps"),
    panel("timeseries", "HTTP p95 latency by service",
          [('histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))', "{{application}}")],
          12, 5, 12, 8, unit="s"),
    panel("timeseries", "Redirects by outcome",
          [("sum by (outcome) (rate(shortener_redirects_total[1m]))", "{{outcome}}")], 0, 13, 8, 8, unit="reqps"),
    panel("timeseries", "JVM heap used",
          [('sum by (application) (jvm_memory_used_bytes{area="heap"})', "{{application}}")], 8, 13, 8, 8, unit="bytes"),
    panel("timeseries", "DB connections in use",
          [("sum by (application) (hikaricp_connections_active)", "{{application}}")], 16, 13, 8, 8),
    panel("stat", "LLM circuit breaker",
          ['max(resilience4j_circuitbreaker_state{name="llm", state=~"open|forced_open"}) * 2 '
           '+ max(resilience4j_circuitbreaker_state{name="llm", state="half_open"})'],
          0, 21, 6, 6,
          thresholds={"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "orange", "value": 1},
                                                    {"color": "red", "value": 2}]},
          description="ORCH-023. 0 = closed (calls flow), 1 = half-open (trial calls), 2 = open (failing fast)."),
    panel("timeseries", "LLM calls through the breaker",
          [('sum by (kind) (rate(resilience4j_circuitbreaker_calls_seconds_count{name="llm"}[5m]))', "{{kind}}"),
           ('sum(rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="llm"}[5m]))', "rejected (open)")],
          6, 21, 9, 6, unit="reqps"),
    panel("timeseries", "LLM retries by outcome",
          [('sum by (kind) (rate(resilience4j_retry_calls_total{name="llm"}[5m]))', "{{kind}}")],
          15, 21, 9, 6, unit="reqps"),
])

OUT.mkdir(exist_ok=True)
for name, board in [("orchestrator-overview", overview), ("governance", governance), ("service-health", health)]:
    (OUT / f"{name}.json").write_text(json.dumps(board, indent=2) + "\n")
    print(f"wrote dashboards/{name}.json ({len(board['panels'])} panels)")

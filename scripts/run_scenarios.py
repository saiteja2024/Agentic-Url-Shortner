#!/usr/bin/env python3
"""Runs the three assessment scenarios against a running orchestrator and saves the evidence.

Each scenario is driven through the public HTTP API exactly as a human operator would: start the
run, wait for it to pause, then approve / reject / answer. The run's audit log, report and final
state are saved to scenarios/<name>/ together with a readable summary.md.

    python3 scripts/run_scenarios.py                 # all three
    python3 scripts/run_scenarios.py brownfield      # just one

Needs the orchestrator on http://localhost:8080 (override with ORCHESTRATOR_URL) and the API key
in ORCHESTRATOR_API_KEY or .env. Standard library only.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = os.environ.get("ORCHESTRATOR_URL", "http://localhost:8080").rstrip("/")
OPERATOR = os.environ.get("OPERATOR", "sai")
TIMEOUT_S = 300


def api_key() -> str:
    key = os.environ.get("ORCHESTRATOR_API_KEY")
    env = ROOT / ".env"
    if not key and env.exists():
        for line in env.read_text().splitlines():
            if line.startswith("ORCHESTRATOR_API_KEY="):
                key = line.split("=", 1)[1].strip()
    if not key:
        sys.exit("ORCHESTRATOR_API_KEY is not set (environment or .env)")
    return key


KEY = api_key()


def call(method: str, path: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if method != "GET":
        request.add_header("X-API-Key", KEY)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()}") from e


def wait_for(run_id: str, predicate, what: str) -> dict:
    deadline = time.time() + TIMEOUT_S
    while time.time() < deadline:
        run = call("GET", f"/api/v1/runs/{run_id}")
        if predicate(run):
            return run
        time.sleep(1)
    raise SystemExit(f"timed out waiting for {what}")


def waiting(node: str):
    return lambda run: any(n["id"] == node and n["status"] == "WAITING_APPROVAL" for n in run["nodes"])


def finished(run: dict) -> bool:
    return run["status"] != "RUNNING"


def log(step: str) -> None:
    print(f"  · {step}", flush=True)


def decide(run_id: str, node: str, action: str, **body) -> None:
    call("POST", f"/api/v1/runs/{run_id}/nodes/{node}/{action}", {"by": OPERATOR, **body})


# ---- scenarios --------------------------------------------------------------------------------------

def greenfield() -> str:
    run = call("POST", "/api/v1/runs", {
        "scenario": "greenfield", "mode": "GREENFIELD",
        "request": "Add click analytics: record clicks with their referrer and report the top N referrers for a link."})
    rid = run["id"]
    wait_for(rid, waiting("design"), "design approval")
    log("design is waiting for approval → approve")
    decide(rid, "design", "approve")
    wait_for(rid, waiting("release"), "merge approval")
    log("release requests the merge (policy 'merge') → approve")
    decide(rid, "release", "approve")
    wait_for(rid, finished, "run end")
    return rid


def brownfield() -> str:
    run = call("POST", "/api/v1/runs", {
        "scenario": "brownfield", "mode": "BROWNFIELD",
        "request": "Links must support an optional expiry; an expired link must no longer resolve."})
    rid = run["id"]
    wait_for(rid, waiting("design"), "design approval")
    log("impact analysis done; design waiting → approve")
    decide(rid, "design", "approve")
    wait_for(rid, waiting("test"), "escalation")
    approvals = call("GET", f"/api/v1/runs/{rid}/approvals")
    log(f"test escalated: {approvals[0]['reasons'][0][:110]}…")
    log("reject the escalation (the test misreads REQ-002) → node fails, branch stays rolled back")
    decide(rid, "test", "reject", reason="ExpiryTest asserts the wrong behaviour for REQ-002; needs a human fix")
    wait_for(rid, finished, "run end")
    return rid


def ambiguous() -> str:
    run = call("POST", "/api/v1/runs", {
        "scenario": "ambiguous", "mode": "GREENFIELD",
        "request": "Make the short links better for our marketing team."})
    rid = run["id"]
    wait_for(rid, waiting("requirements"), "clarifying questions")
    questions = call("GET", f"/api/v1/runs/{rid}/approvals")[0]["questions"]
    for q in questions:
        log(f"question {q['id']}: {q['text']}")
    log("answer Q1 only; Q2 and Q3 become recorded assumptions")
    decide(rid, "requirements", "answers", answers={"Q1": "Yes: aliases of 3-32 characters from a-z, 0-9 and hyphen."})
    wait_for(rid, waiting("design"), "design approval")
    decide(rid, "design", "approve")
    wait_for(rid, waiting("release"), "merge approval")
    decide(rid, "release", "approve")
    wait_for(rid, finished, "first pass")
    log("first pass done → re-plan requirements with a revised request")
    call("POST", f"/api/v1/runs/{rid}/nodes/requirements/replan",
         {"by": OPERATOR, "input": "Also block the reserved words admin and api."})
    wait_for(rid, waiting("design"), "design re-approval")
    log("design re-ran (its input hash changed) → approve again")
    decide(rid, "design", "approve")
    wait_for(rid, finished, "re-plan end")
    return rid


SCENARIOS = {"greenfield": greenfield, "brownfield": brownfield, "ambiguous": ambiguous}


# ---- evidence ---------------------------------------------------------------------------------------

def summary(name: str, run: dict, report: dict, events: list) -> str:
    seconds = (report.get("durationMs") or 0) / 1000
    lines = [
        f"# Scenario: {name}",
        "",
        f"- Run `{run['id']}` on branch `{run['branch']}` — **{report['status']}** in {seconds:.1f} s",
        f"- Request: {run['request']}",
        f"- Retries: {report['retries']} · Rollbacks: {len(report['rollbacks'])} · "
        f"LLM tokens: {report['tokens']['input'] + report['tokens']['output']} of {report['tokens']['budget']}",
        f"- Audit events: {len(events)} (append-only, sequence {events[0]['seq']}–{events[-1]['seq']})",
        "",
        "## Nodes",
        "",
        "| Node | Status | Attempts | Failure |",
        "|---|---|---|---|",
    ]
    for n in report["nodes"]:
        lines.append(f"| {n['id']} | {n['status']} | {n['attempts']} | {(n.get('failureReason') or '').replace('|', '/')[:120]} |")
    lines += ["", "## Gates", "", "| Node | Gate | Attempt | Outcome |", "|---|---|---|---|"]
    for g in report["gates"]:
        lines.append(f"| {g['nodeId']} | {g['gate']} | {g.get('attempt', '')} | {g['outcome']} |")
    lines += ["", "## Human decisions", "", "| Node | Kind | Decision | By | Waited |", "|---|---|---|---|---|"]
    for a in report["approvals"]:
        lines.append(f"| {a['nodeId']} | {a.get('kind', '')} | {a['decision']} | {a['by']} | {(a.get('waitMs') or 0) / 1000:.1f} s |")
    if report["clarifications"]:
        lines += ["", "## Clarifications and assumptions", ""]
        for c in report["clarifications"]:
            lines.append(f"- **{c['questionId']}** {c['question']} → "
                         + (f"answer: {c['answer']}" if c.get("answer") else f"assumption: {c['assumption']}"))
    if report["rollbacks"]:
        lines += ["", "## Rollbacks", ""]
        for r in report["rollbacks"]:
            lines.append(f"- `{r['nodeId']}` reset to `{r['checkpoint'][:12]}`: {r.get('reason', '')[:160]}")
    replans = [e for e in events if e["type"] == "REPLAN_COMPARED"]
    if replans:
        lines += ["", "## Re-plan", ""]
        for e in replans:
            lines.append(f"- `{e['nodeId']}` re-ran; output changed: {e['payload']['outputChanged']} → downstream {e['payload']['downstream']}")
    return "\n".join(lines) + "\n"


def save(name: str, run_id: str) -> None:
    out = ROOT / "scenarios" / name
    out.mkdir(parents=True, exist_ok=True)
    run = call("GET", f"/api/v1/runs/{run_id}")
    report = call("GET", f"/api/v1/runs/{run_id}/report")
    events = call("GET", f"/api/v1/runs/{run_id}/events")
    (out / "run.json").write_text(json.dumps(run, indent=2) + "\n")
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    (out / "audit-log.json").write_text(json.dumps(events, indent=2) + "\n")
    (out / "summary.md").write_text(summary(name, run, report, events))
    print(f"  ✓ {report['status']} — saved scenarios/{name}/ ({len(events)} events)")


def main(argv: list[str]) -> int:
    names = argv or list(SCENARIOS)
    for name in names:
        if name not in SCENARIOS:
            sys.exit(f"unknown scenario {name!r}; choose from {list(SCENARIOS)}")
    for name in names:
        print(f"▶ {name}")
        save(name, SCENARIOS[name]())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

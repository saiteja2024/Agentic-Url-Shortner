// Re-record docs/demo/walkthrough.webm against the running stack (docker compose + both apps).
// Setup once: (cd scripts && npm install --no-save playwright@1.63.0 && npx playwright install chromium)
// Run: node scripts/record-walkthrough.js "$PWD"  → prints the path of the new .webm
// Scripted, captioned walkthrough of every feature, recorded with Playwright's video capture.
// Usage: node record.js <repo root>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const ROOT = process.argv[2];
const env = Object.fromEntries(fs.readFileSync(path.join(ROOT, '.env'), 'utf8').split('\n')
  .filter(l => l.includes('=') && !l.startsWith('#'))
  .map(l => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
const ORCH = 'http://localhost:8080';
const SHORT = 'http://localhost:8081';
const O2_AUTH = 'Basic ' + Buffer.from(`${env.ZO_ROOT_USER_EMAIL}:${env.ZO_ROOT_USER_PASSWORD}`).toString('base64');
const W = 1440, H = 900;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = m => console.log(new Date().toISOString().slice(11, 19), m);

async function api(p) { return (await fetch(ORCH + p)).json(); }
async function waitFor(runId, pred, what, timeoutMs = 240000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const run = await api(`/api/v1/runs/${runId}`);
    if (pred(run)) return run;
    await sleep(500);
  }
  throw new Error('timed out waiting for ' + what);
}
const nodeIs = (id, status) => run => run.nodes.some(n => n.id === id && n.status === status);
const finished = run => run.status !== 'RUNNING';

async function caption(page, title, sub = '') {
  await page.evaluate(([t, s]) => {
    let el = document.getElementById('__cap');
    if (!el) { el = document.createElement('div'); el.id = '__cap'; document.documentElement.appendChild(el); }
    el.style.cssText = 'position:fixed;left:50%;bottom:26px;transform:translateX(-50%);z-index:2147483647;'
      + 'background:rgba(14,19,28,.9);color:#fff;font:600 21px/1.35 system-ui,-apple-system,sans-serif;'
      + 'padding:12px 22px;border-radius:12px;max-width:82%;box-shadow:0 8px 28px rgba(0,0,0,.35);'
      + 'text-align:center;pointer-events:none';
    el.innerHTML = '<div>' + t + '</div>' + (s ? '<div style="font-weight:400;font-size:16px;opacity:.86;margin-top:4px">' + s + '</div>' : '');
  }, [title, sub]);
}

async function card(page, title, lines, ms = 6000) {
  await page.setContent(`<html><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
    background:linear-gradient(135deg,#101826,#1d2b45);color:#fff;font-family:system-ui,-apple-system,sans-serif">
    <div style="max-width:1000px"><div style="font-size:15px;letter-spacing:.14em;text-transform:uppercase;opacity:.7">Agentic SDLC Orchestrator</div>
    <h1 style="font-size:52px;margin:10px 0 24px">${title}</h1>
    <ul style="font-size:23px;line-height:1.6;opacity:.92;padding-left:26px">${lines.map(l => `<li>${l}</li>`).join('')}</ul></div></body></html>`);
  await sleep(ms);
}

async function o2TraceUrl(runId, scenario) {
  const now = Date.now() * 1000;
  const body = { query: { sql: `SELECT trace_id, start_time, end_time FROM "default" WHERE run_id = '${runId}' AND operation_name = 'run ${scenario}'`,
    start_time: now - 3600e6, end_time: now, from: 0, size: 1 } };
  for (let i = 0; i < 30; i++) {
    const r = await fetch('http://localhost:5080/api/default/_search?type=traces', {
      method: 'POST', headers: { Authorization: O2_AUTH, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const hits = (await r.json()).hits || [];
    if (hits.length) {
      const h = hits[0];
      return `http://localhost:5080/web/traces/trace-details?stream=default&trace_id=${h.trace_id}`
        + `&from=${Math.floor(h.start_time / 1000) - 60e6}&to=${Math.floor(h.end_time / 1000) + 60e6}&org_identifier=default`;
    }
    await sleep(2000);
  }
  throw new Error('trace not found in OpenObserve');
}

(async () => {
  const outDir = path.join(__dirname, 'video');
  fs.rmSync(outDir, { recursive: true, force: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: W, height: H }, recordVideo: { dir: outDir, size: { width: W, height: H } } });
  const page = await context.newPage();
  let dialogAnswer = '';
  page.on('dialog', d => d.accept(dialogAnswer));
  const runs = {};

  // ---- intro ---------------------------------------------------------------------------------------
  log('intro');
  await card(page, 'From requirement to reviewed change — under governance', [
    'Eight agents run as a dependency graph: requirements → impact analysis → design → implement ‖ test plan → test → docs → release',
    'Gates check every output; humans approve high-impact steps and answer clarifying questions',
    'Retries with feedback, rollback, escalation, kill switch, budgets, minimal re-plan',
    'Append-only audit log · traces · metrics · dashboards · alerts',
  ], 9000);

  // ---- shortener -----------------------------------------------------------------------------------
  log('shortener');
  await card(page, 'Part 1 — the URL shortener', [
    'Contract-first: Java interfaces are generated from a hand-written OpenAPI 3.1 spec',
    'Base62 codes, aliases, expiry, click analytics, RFC 9457 errors',
    'SSRF guardrails: loopback, private ranges, cloud metadata — in every IP notation',
  ], 6000);
  await page.goto(SHORT + '/swagger-ui/index.html');
  await page.waitForSelector('#operations-Urls-createShortUrl');
  await caption(page, 'Swagger UI renders the hand-written contract', 'specs/shortener/openapi.yaml — 17 requirements, each traced to passing tests');
  await sleep(3500);
  const op = page.locator('#operations-Urls-createShortUrl');
  await op.locator('.opblock-summary').click();
  await op.locator('.try-out__btn').click();
  const bodyBox = op.locator('textarea.body-param__text');
  await bodyBox.fill('{\n  "url": "https://www.example.org/docs/getting-started"\n}');
  await caption(page, 'Create a short link');
  await sleep(1500);
  await op.locator('button.execute').click();
  await op.locator('.live-responses-table .response-col_status').first().waitFor();
  await op.locator('.live-responses-table').scrollIntoViewIfNeeded();
  const created = JSON.parse(await op.locator('.live-responses-table .microlight').first().innerText());
  await caption(page, `201 Created — code "${created.code}"`, 'Location header points to the stats resource');
  await sleep(4000);

  await bodyBox.fill('{\n  "url": "http://0x7f000001:8080/admin"\n}');
  await op.locator('button.execute').click();
  await sleep(1200);
  await op.locator('.live-responses-table').scrollIntoViewIfNeeded();
  await caption(page, 'SSRF guardrail: 0x7f000001 is 127.0.0.1 in hex → 400 ProblemDetail', '"localhost and loopback addresses are not allowed"');
  await sleep(5000);
  await bodyBox.fill('{\n  "url": "http://169.254.169.254/latest/meta-data/"\n}');
  await op.locator('button.execute').click();
  await sleep(1200);
  await caption(page, 'Cloud metadata endpoint → 400', '"cloud metadata endpoints are not allowed"');
  await sleep(4000);

  for (let i = 0; i < 3; i++) await fetch(`${SHORT}/${created.code}`, { redirect: 'manual', headers: { Referer: 'https://news.example.com/' } });
  await page.goto(`${SHORT}/api/v1/urls/${created.code}/stats`);
  await caption(page, 'Click analytics after three redirects', 'totalClicks, lastClickedAt and top referrers — read from one consistent snapshot');
  await sleep(5000);

  // ---- orchestrator console ------------------------------------------------------------------------
  log('greenfield');
  await card(page, 'Part 2 — Greenfield run', [
    'Request: add click analytics to the target project',
    'Design approval (human checkpoint), then implement ‖ test-plan in parallel',
    'Real gates: mvn compile, tests, JaCoCo coverage, allowed paths, secret scan',
    'The merge is a policy-driven human decision',
  ], 6000);
  await page.goto(ORCH + '/');
  await page.fill('#apiKey', env.ORCHESTRATOR_API_KEY);
  await page.dispatchEvent('#apiKey', 'change');
  await page.fill('#operator', 'Saiteja');
  await page.dispatchEvent('#operator', 'change');
  await page.selectOption('#scenario', 'greenfield');
  await caption(page, 'Operator console — start a greenfield run', 'Mutations require the API key (X-API-Key); reads are open');
  await sleep(2500);
  await page.click('#startForm button[type=submit]');
  await sleep(1500);
  runs.greenfield = (await api('/api/v1/runs'))[0].id;
  await caption(page, 'Requirements agent writes REQ-001…003; the requirements-ids gate checks Given/When/Then');
  await waitFor(runs.greenfield, nodeIs('design', 'WAITING_APPROVAL'), 'design approval');
  await sleep(2500);
  await caption(page, 'Design waits for a human — every machine check already passed', 'impact-analysis was skipped: it only runs for brownfield changes (audited as NODE_SKIPPED)');
  await sleep(5000);
  await page.click('#approvals button:has-text("Approve")');
  await caption(page, 'Approved → implement and test-plan run in parallel; test waits for both (join barrier)', 'Gates run real Maven builds in the run\'s own git branch');
  await waitFor(runs.greenfield, nodeIs('release', 'WAITING_APPROVAL'), 'merge approval');
  await sleep(1500);
  await caption(page, 'Release requests the merge — approval policy makes that a human decision', 'policy \'merge\' matched action:merge');
  await sleep(5000);
  await page.click('#approvals button:has-text("Approve")');
  await waitFor(runs.greenfield, finished, 'greenfield end');
  await sleep(2500);
  await caption(page, 'SUCCEEDED — the run branch is merged into main');
  await sleep(3000);
  await page.locator('#report').scrollIntoViewIfNeeded();
  await caption(page, 'The report is rebuilt only from the append-only audit log', 'Nodes, attempts, durations, every gate result, who approved and how long they waited');
  await page.mouse.wheel(0, 500);
  await sleep(4000);
  await page.mouse.wheel(0, 700);
  await caption(page, 'Artifacts carry SHA-256 hashes and their parents\' hashes — the lineage used for re-planning');
  await sleep(4500);
  await page.evaluate(() => window.scrollTo(0, 0));

  log('brownfield');
  await card(page, 'Part 3 — Brownfield run', [
    'Request: links must support an optional expiry',
    'Impact analysis names files that must exist in the codebase',
    'A regression is caught, fed back to the agent, and fixed on retry',
    'A test agent that keeps failing is rolled back and escalated to a human',
  ], 6000);
  await page.goto(ORCH + '/');
  await page.selectOption('#scenario', 'brownfield');
  await page.click('#startForm button[type=submit]');
  await sleep(1500);
  runs.brownfield = (await api('/api/v1/runs'))[0].id;
  await caption(page, 'Brownfield: impact analysis runs and is verified against the real files');
  await waitFor(runs.brownfield, nodeIs('design', 'WAITING_APPROVAL'), 'design approval');
  await sleep(2000);
  await page.click('#approvals button:has-text("Approve")');
  await caption(page, 'Implement attempt 1 breaks an existing test → tests-pass gate fails', 'The workspace is reset to the checkpoint and the gate output is fed into attempt 2');
  await waitFor(runs.brownfield, nodeIs('test', 'WAITING_APPROVAL'), 'escalation');
  await sleep(2000);
  await caption(page, 'Attempt 2 recovered (MTTR recorded). The test agent failed 3 times → rolled back and ESCALATED', 'Approving would accept the node; rejecting fails it with the branch already rolled back');
  await sleep(6000);
  dialogAnswer = 'ExpiryTest asserts the wrong behaviour for REQ-002 — needs a human fix';
  await page.click('#approvals button:has-text("Reject")');
  await waitFor(runs.brownfield, finished, 'brownfield end');
  await sleep(2000);
  await page.click('.tabs button[data-tab="events"]');
  await sleep(1500);
  await page.locator('#events').scrollIntoViewIfNeeded();
  await caption(page, 'Audit log: NODE_RETRY · NODE_RECOVERED · ROLLBACK · APPROVAL_DECIDED', 'Append-only — a database trigger rejects UPDATE, DELETE and TRUNCATE, even for the owner');
  await page.locator('#events').evaluate(el => el.scrollTo({ top: el.scrollHeight * 0.45, behavior: 'smooth' }));
  await sleep(6000);
  await page.click('.tabs button[data-tab="report"]');
  await page.evaluate(() => window.scrollTo(0, 0));

  log('ambiguous');
  await card(page, 'Part 4 — Ambiguous request', [
    'Request: “make the short links better for our marketing team”',
    'The requirements agent asks clarifying questions and the run pauses',
    'Unanswered questions become explicit, audited assumptions',
    'Re-plan: re-run only what actually changed',
  ], 6000);
  await page.goto(ORCH + '/');
  await page.selectOption('#scenario', 'ambiguous');
  await page.click('#startForm button[type=submit]');
  await sleep(1500);
  runs.ambiguous = (await api('/api/v1/runs'))[0].id;
  await waitFor(runs.ambiguous, nodeIs('requirements', 'WAITING_APPROVAL'), 'questions');
  await sleep(2000);
  await caption(page, 'Three clarifying questions — the run pauses instead of guessing');
  await sleep(4500);
  await page.fill('#approvals [data-question="Q1"]', 'Yes: 3–32 characters from a-z, 0-9 and hyphen');
  await caption(page, 'Answer Q1; leave Q2 and Q3 blank → they become recorded assumptions');
  await sleep(3000);
  await page.click('#approvals button:has-text("Submit answers")');
  await waitFor(runs.ambiguous, nodeIs('design', 'WAITING_APPROVAL'), 'design approval');
  await sleep(1500);
  await page.click('#approvals button:has-text("Approve")');
  await waitFor(runs.ambiguous, nodeIs('release', 'WAITING_APPROVAL'), 'merge approval');
  await sleep(1000);
  await page.click('#approvals button:has-text("Approve")');
  await waitFor(runs.ambiguous, finished, 'first pass');
  await sleep(2000);
  await page.locator('#report').scrollIntoViewIfNeeded();
  await page.evaluate(() => { const h = [...document.querySelectorAll('#report h2')].find(e => e.textContent.startsWith('Clarifications')); if (h) h.scrollIntoView({ block: 'center' }); });
  await caption(page, 'Q1 answered by Saiteja; Q2 and Q3 recorded as assumptions');
  await sleep(5000);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.selectOption('#replanNode', 'requirements');
  await page.fill('#replanInput', 'Also block the reserved words admin and api');
  await caption(page, 'Requirements changed after delivery → re-plan from the requirements node');
  await sleep(3500);
  await page.click('#replanBtn');
  await caption(page, 'requirements and design go STALE and re-run; design needs approval again');
  await waitFor(runs.ambiguous, nodeIs('design', 'WAITING_APPROVAL'), 'design re-approval');
  await sleep(2500);
  await page.click('#approvals button:has-text("Approve")');
  await waitFor(runs.ambiguous, finished, 're-plan end');
  await sleep(2000);
  await caption(page, 'Design\'s output hash did not change → implement, test, docs and release were NOT re-run', 'Minimal re-plan driven by content hashes (REPLAN_COMPARED: outputChanged=false)');
  await sleep(6500);

  log('kill switch');
  await page.selectOption('#scenario', 'greenfield');
  await page.click('#startForm button[type=submit]');
  await sleep(1500);
  runs.killed = (await api('/api/v1/runs'))[0].id;
  await waitFor(runs.killed, nodeIs('design', 'WAITING_APPROVAL'), 'design wait');
  await sleep(1500);
  await caption(page, 'Kill switch: stop a run mid-flight');
  await sleep(2500);
  dialogAnswer = 'Wrong requirement — stopping before any code is written';
  await page.click('#killBtn');
  await waitFor(runs.killed, finished, 'cancel');
  await sleep(2000);
  await caption(page, 'CANCELLED — pending approval cancelled, nothing new started, reason recorded', 'Token budget, time budget and application shutdown stop runs the same way');
  await sleep(6000);

  // ---- observability -------------------------------------------------------------------------------
  log('observability');
  await card(page, 'Part 5 — Observability', [
    'OpenObserve: one trace per run — run → node → gate / approval',
    'Grafana: Orchestrator Overview, Governance, Service Health',
    'Prometheus: three alert rules',
  ], 5000);
  await page.goto('http://localhost:5080/');
  await page.fill('[data-test="login-user-id"]', env.ZO_ROOT_USER_EMAIL);
  await page.fill('[data-test="login-password"]', env.ZO_ROOT_USER_PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForURL(/\/web\//);
  await page.goto(await o2TraceUrl(runs.brownfield, 'brownfield'));
  await sleep(5000);
  await caption(page, 'OpenObserve: the whole brownfield run is one trace', 'POST /runs → run → node → gate (one span per attempt) and approval spans, tagged with attempt and outcome');
  await sleep(8000);

  await page.goto('http://localhost:3000/login');
  await page.fill('input[name="user"]', env.GRAFANA_ADMIN_USER || 'admin');
  await page.fill('input[name="password"]', env.GRAFANA_ADMIN_PASSWORD);
  await page.click('button[type="submit"]');
  await page.waitForURL(u => !u.toString().includes('/login'));
  for (const [uid, text, sub] of [
    ['orchestrator-overview', 'Grafana — Orchestrator Overview', 'Success rate, active runs, MTTR, tokens, run and node durations'],
    ['orchestrator-governance', 'Grafana — Governance', 'Retries, rollbacks, gate failures by gate, human decisions and approval waits'],
    ['service-health', 'Grafana — Service Health', 'Request rate, p95 latency, 5xx ratio, shortener metrics, JVM and DB pool'],
  ]) {
    await page.goto(`http://localhost:3000/d/${uid}?orgId=1&kiosk&from=now-30m&to=now`);
    await sleep(5000);
    await caption(page, text, sub);
    await sleep(6000);
  }

  await page.goto('http://localhost:9090/alerts');
  await sleep(2500);
  await caption(page, 'Prometheus alert rules', 'Run success rate < 80 % · more than 2 rollbacks in 15 min · p95 latency > 500 ms');
  await sleep(6000);

  // ---- close ---------------------------------------------------------------------------------------
  await card(page, 'Limitations — stated plainly', [
    'The LLM runs in mock mode by default: recorded replies keep demos and CI deterministic; Claude is a config switch',
    'Runs target a small seed project; gates run on the host (no per-run sandbox yet)',
    'Single shared API key; runs live in memory (no resume after restart)',
    'Next: Boot 4, sandboxed runs, OIDC approvals, durable execution, real-repo PRs',
  ], 9000);

  await context.close();
  await browser.close();
  const file = fs.readdirSync(outDir).find(f => f.endsWith('.webm'));
  fs.writeFileSync(path.join(__dirname, 'runs.json'), JSON.stringify(runs, null, 2));
  console.log('VIDEO', path.join(outDir, file));
})().catch(e => { console.error(e); process.exit(1); });

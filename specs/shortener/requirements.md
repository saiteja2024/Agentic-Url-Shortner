# URL Shortener — Requirements

Contract: [`openapi.yaml`](openapi.yaml). Every requirement below has a stable ID; tests
that verify it carry `@Tag("<ID>")` and a `@DisplayName` starting with `<ID>:`.

Conventions used in the criteria:
- "ProblemDetail" means an RFC 9457 body with `Content-Type: application/problem+json`
  and at least `type`, `title`, `status`.
- Short codes are Base62 (`[0-9A-Za-z]`); custom aliases are `[A-Za-z0-9_-]{3,32}`.

---

## Create

### SHORT-001 — Create a short URL with a generated code
Status: Implemented

- **Given** a valid absolute `https://` or `http://` URL
- **When** a client sends `POST /api/v1/urls` with `{"url": "<url>"}`
- **Then** the response is `201 Created`
- **And** the body contains `code` (7 Base62 characters), `shortUrl`, `longUrl` (as submitted), `createdAt`
- **And** the `Location` header points to the stats resource `/api/v1/urls/{code}/stats`

### SHORT-002 — Retry on code collision
Status: Implemented

- **Given** the generated code already exists in the store
- **When** a URL is created
- **Then** the service generates a new code and retries, up to **5 attempts**
- **And** the client receives `201` with a code that did not previously exist
- **Given** all 5 attempts collide
- **Then** the response is `503 Service Unavailable` ProblemDetail and the failure is logged and counted as a metric

### SHORT-003 — Custom alias
Status: Implemented

- **Given** a request with `"alias": "my-link"` matching `^[A-Za-z0-9_-]{3,32}$`
- **When** the URL is created
- **Then** the response is `201` and `code` equals the alias
- **Given** the alias is already taken
- **Then** the response is `409 Conflict` ProblemDetail and the existing mapping is unchanged
- **Given** the alias does not match the pattern, or is a reserved word (`api`, `actuator`, `swagger-ui`, `v3`, `openapi`, `health`, `error`)
- **Then** the response is `400 Bad Request` ProblemDetail naming the `alias` field

### SHORT-004 — Optional expiry
Status: Implemented

- **Given** a request with `"expiresAt"` as an ISO-8601 timestamp in the future
- **When** the URL is created
- **Then** the response is `201` and echoes `expiresAt`
- **Given** `expiresAt` is now or in the past
- **Then** the response is `400 Bad Request` ProblemDetail naming the `expiresAt` field
- **Given** no `expiresAt`
- **Then** the link never expires and `expiresAt` is omitted from responses

### SHORT-005 — Concurrent creates are safe
Status: Implemented

- **Given** 50 concurrent `POST /api/v1/urls` requests (mixed generated codes and distinct aliases)
- **When** they complete
- **Then** every request receives `201`, all returned codes are distinct, and no request returns `5xx`
- **Given** concurrent requests for the **same** alias
- **Then** exactly one receives `201` and all others receive `409`

## Redirect

### SHORT-006 — Redirect to the long URL
Status: Implemented

- **Given** an existing, unexpired code
- **When** a client sends `GET /{code}`
- **Then** the response is `302 Found` with `Location: <longUrl>`
- **And** `Cache-Control: no-store` is set, so every visit reaches the service and is counted

### SHORT-007 — Record each click with its referrer
Status: Implemented

- **Given** an existing, unexpired code
- **When** `GET /{code}` is served
- **Then** one click is recorded with its timestamp and the `Referer` header (or none if absent), truncated to 2048 characters
- **And** a failure to record the click does not fail the redirect (it is logged and counted)

### SHORT-008 — Unknown code
Status: Implemented

- **Given** a code that does not exist
- **When** `GET /{code}` or `GET /api/v1/urls/{code}/stats` is requested
- **Then** the response is `404 Not Found` ProblemDetail and no click is recorded

### SHORT-009 — Expired link
Status: Implemented

- **Given** a code whose `expiresAt` is in the past
- **When** `GET /{code}` is requested
- **Then** the response is `410 Gone` ProblemDetail and no click is recorded

## Stats

### SHORT-010 — Link statistics
Status: Implemented

- **Given** an existing code (expired or not)
- **When** a client sends `GET /api/v1/urls/{code}/stats`
- **Then** the response is `200 OK` with `code`, `longUrl`, `createdAt`, `expiresAt` (if set), `expired`, `totalClicks`, `lastClickedAt` (if any) and `topReferrers`
- **And** `topReferrers` lists up to 10 referrers ordered by click count descending, with clicks without a referrer grouped as `"(direct)"`

## URL validation (SSRF / abuse guardrails)

All of the following reject the request with `400 Bad Request` ProblemDetail whose
`errors[]` names the `url` field, and nothing is persisted.

### SHORT-011 — Only http(s) absolute URLs
Status: Implemented

- **Given** a URL that is not absolute, or whose scheme is not `http`/`https` (e.g. `javascript:`, `file:`, `ftp:`, `data:`)
- **When** it is submitted
- **Then** it is rejected with `400`
- **Given** a URL with embedded credentials (`https://user:pass@host`)
- **Then** it is rejected with `400`

### SHORT-012 — Block localhost and loopback
Status: Implemented

- **Given** a URL whose host is `localhost`, `*.localhost`, `127.0.0.0/8`, `::1`, or `0.0.0.0`
- **When** it is submitted
- **Then** it is rejected with `400`

### SHORT-013 — Block private and link-local addresses
Status: Implemented

- **Given** a URL whose host is, or resolves to, an address in `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, `169.254.0.0/16`, `fc00::/7` or `fe80::/10`
- **When** it is submitted
- **Then** it is rejected with `400`
- **And** alternative IP notations (decimal `http://2130706433`, octal, hex, IPv4-mapped IPv6) are normalised before the check

### SHORT-014 — Block cloud metadata endpoints
Status: Implemented

- **Given** a URL whose host is `169.254.169.254`, `fd00:ec2::254`, `metadata.google.internal`, or `metadata.azure.com`
- **When** it is submitted
- **Then** it is rejected with `400`

### SHORT-015 — Request shape validation
Status: Implemented

- **Given** a body with `url` missing, blank, or longer than 2048 characters, or malformed JSON
- **When** it is submitted
- **Then** the response is `400 Bad Request` ProblemDetail with an `errors[]` entry per invalid field

## Cross-cutting

### SHORT-016 — Uniform RFC 9457 errors
Status: Implemented

- **Given** any 4xx or 5xx response from the API
- **Then** it has `Content-Type: application/problem+json` and a body matching the `ProblemDetail` schema
- **And** 5xx responses never expose stack traces or internal exception messages

### SHORT-017 — Responses conform to the OpenAPI contract
Status: Implemented

- **Given** the MockMvc test suite
- **When** any request is exercised against `/api/v1/**` or `/{code}`
- **Then** request and response are validated against `openapi.yaml` and any mismatch fails the test

---

## Data model

Postgres schema `shortener`, owned by Flyway (`shortener/src/main/resources/db/migration`).
Schema changes require human approval (`specs/orchestrator/pipeline.yaml` → `approvalPolicy`).

| Table | Column | Type | Notes |
|---|---|---|---|
| `urls` | `id` | `bigint` identity | Primary key |
| | `code` | `varchar(32)` | **Unique**; generated code or alias. The unique index is what makes collision retry (SHORT-002) and alias races (SHORT-005) safe |
| | `long_url` | `varchar(2048)` | As submitted, after validation |
| | `created_at` | `timestamptz` | Set by the service clock |
| | `expires_at` | `timestamptz` null | Null = never expires; must be after `created_at` (check constraint) |
| `clicks` | `id` | `bigint` identity | Primary key |
| | `url_id` | `bigint` | FK → `urls.id`, `ON DELETE CASCADE` |
| | `clicked_at` | `timestamptz` | |
| | `referrer` | `varchar(2048)` null | Null = direct visit, reported as `(direct)` |

Index `clicks (url_id, clicked_at)` serves the stats queries (SHORT-010).

---

## Assumptions & Open Questions

**Assumptions** (made so work can proceed; each is revisitable)

| # | Assumption | Rationale |
|---|---|---|
| A1 | Generated codes are 7 Base62 chars (62⁷ ≈ 3.5 × 10¹² space). | Collision probability stays negligible at prototype scale; retry (SHORT-002) covers the rest. |
| A2 | The same long URL submitted twice gets two different codes (no de-duplication). | Each link has independent expiry and stats; de-dup would leak who else shortened a URL. |
| A3 | Hostnames are resolved once at creation time and rejected if **any** resolved address is blocked; unresolvable hosts are accepted. | The shortener never fetches the target, so SSRF exposure is to downstream consumers; DNS rebinding after creation is out of scope (see Q3). |
| A4 | Stats remain readable after expiry. | Owners still want analytics for finished campaigns. |
| A5 | No authentication on the shortener API in the prototype. | Out of assessment scope; would be API-key/OAuth in production (see Q1). |
| A6 | Clicks are recorded synchronously in the redirect request. | Simple and consistent at prototype scale; production would buffer asynchronously (see Q5). |
| A7 | Aliases are case-sensitive, like generated codes. | Base62 is case-sensitive; one rule for both. |

**Open questions** (need a product/security owner decision)

| # | Question | Default until answered |
|---|---|---|
| Q1 | Who may create links — anonymous, API-key holders, or authenticated users? Is per-client rate limiting required? | A5: anonymous, no rate limit (defaults Q1–Q6 confirmed for the prototype, 2026-10-15) |
| Q2 | Should known-malicious domains (Safe Browsing / blocklists) be rejected? | Not checked |
| Q3 | Must redirects re-validate the target at redirect time (DNS rebinding)? | Validated only at creation (A3) |
| Q4 | Is `302` correct, or should permanent links use `301`/`308` (better for SEO, worse for analytics because browsers cache)? | `302` for every link |
| Q5 | What click-data retention and privacy rules apply (IP addresses, user agents, GDPR)? | Only timestamp + referrer stored; no IP, no UA |
| Q6 | Can a link be updated or deleted after creation? | No — immutable |

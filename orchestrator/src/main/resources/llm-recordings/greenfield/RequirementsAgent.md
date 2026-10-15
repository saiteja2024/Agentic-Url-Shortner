# Requirements — click analytics for linkkit

### REQ-001 — Record a click with its referrer
- **Given** an existing short code
- **When** a click is recorded with a referrer (or none)
- **Then** the click is counted for that code; a missing or blank referrer counts as `(direct)`

### REQ-002 — Report the top N referrers
- **Given** recorded clicks for a code
- **When** the top N referrers are requested
- **Then** at most N referrers are returned, ordered by clicks descending, ties alphabetically
- **And** N < 1 is rejected

### REQ-003 — Totals
- **Given** recorded clicks
- **When** the total for a code is requested
- **Then** it equals the number of recorded clicks; an unknown code has 0

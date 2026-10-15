# Requirements — vanity aliases

Answers received for Q1; Q2 and Q3 unanswered and recorded as assumptions (no expiry, no new analytics).

### REQ-001 — Vanity aliases
- **Given** a URL and an alias of 3–32 characters from `[a-z0-9-]`
- **When** it is shortened with that alias
- **Then** the alias becomes the code

### REQ-002 — Alias conflicts and format
- **Given** an alias that is already taken or has invalid characters
- **When** it is requested
- **Then** the request is rejected with an error naming the problem

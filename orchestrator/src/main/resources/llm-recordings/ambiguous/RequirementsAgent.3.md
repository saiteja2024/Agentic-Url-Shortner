# Requirements — vanity aliases (revised)

Re-planned after a revised request: reserved words must be blocked.

### REQ-001 — Vanity aliases
- **Given** a URL and an alias of 3–32 characters from `[a-z0-9-]`
- **When** it is shortened with that alias
- **Then** the alias becomes the code

### REQ-002 — Alias conflicts and format
- **Given** an alias that is already taken or has invalid characters
- **When** it is requested
- **Then** the request is rejected with an error naming the problem

### REQ-003 — Reserved words
- **Given** an alias equal to a reserved word (`admin`, `api`)
- **When** it is requested
- **Then** it is rejected

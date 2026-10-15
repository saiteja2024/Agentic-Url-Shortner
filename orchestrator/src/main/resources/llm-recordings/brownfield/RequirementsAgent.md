# Requirements — link expiry

### REQ-001 — Optional expiry on creation
- **Given** a URL and an optional time-to-live
- **When** it is shortened
- **Then** the link stores `expiresAt = createdAt + ttl`, or none without a ttl; a zero or negative ttl is rejected

### REQ-002 — Expired links do not resolve
- **Given** a link whose `expiresAt` has passed
- **When** its code is resolved
- **Then** nothing is returned

### REQ-003 — Existing behaviour is preserved
- **Given** existing callers of `shorten(url)`
- **When** they run unchanged
- **Then** they still compile and behave as before (links never expire)

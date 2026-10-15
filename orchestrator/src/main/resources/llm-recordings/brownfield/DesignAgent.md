# Design — link expiry

- `Link(code, url, createdAt, expiresAt)` with `expiresAt` nullable.
- `LinkStore.shorten(url, Duration ttl)`; `shorten(url)` delegates with no ttl (REQ-003).
- `resolve` returns empty when `expiresAt` is not after `clock.instant()` (REQ-002).

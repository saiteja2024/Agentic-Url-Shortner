# Design — ClickStats

- New class `dev.linkkit.ClickStats`, independent of `LinkStore` (no change to existing code).
- Storage: `ConcurrentHashMap<code, ConcurrentHashMap<referrer, LongAdder>>` — lock-free counting.
- API: `record(code, referrer)`, `total(code)`, `topReferrers(code, n)` returning entries sorted
  by count desc, then referrer asc.
- Validation: `code` non-null; `n >= 1`. Blank/null referrer is stored as `(direct)`.
- Risks: unbounded referrer cardinality (acceptable for the in-memory prototype).

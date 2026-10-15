# Test plan — link expiry

| Requirement | Test |
|---|---|
| REQ-001 | ttl stored as createdAt + ttl; zero/negative ttl rejected |
| REQ-002 | link resolves before expiry, not at/after it (mutable clock) |
| REQ-003 | existing LinkStoreTest passes unchanged |

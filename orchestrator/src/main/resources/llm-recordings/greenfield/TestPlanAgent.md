# Test plan — ClickStats

| Requirement | Test |
|---|---|
| REQ-001 | record with referrer, null and blank referrer → `(direct)`; null code rejected |
| REQ-002 | ordering by count then name; limit N; N < 1 rejected; unknown code → empty |
| REQ-003 | total equals recorded clicks; unknown code → 0 |

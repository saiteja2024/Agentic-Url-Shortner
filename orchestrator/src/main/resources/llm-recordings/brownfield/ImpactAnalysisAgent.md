# Impact analysis — link expiry

## Impacted files
- src/main/java/dev/linkkit/Link.java
- src/main/java/dev/linkkit/LinkStore.java
- src/test/java/dev/linkkit/LinkStoreTest.java

## Notes
- `Link` gains a nullable `expiresAt` component; every constructor call site changes.
- `LinkStore.resolve` must consult the clock; `shorten(url)` must stay source-compatible (REQ-003).
- No persistence, API or dependency changes.

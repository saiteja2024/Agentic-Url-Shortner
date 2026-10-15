# Design — vanity aliases

- `LinkStore.shortenWithAlias(url, alias)`; existing `shorten(url)` unchanged.
- Validation order: pattern `[a-z0-9-]{3,32}`, reserved-word list (`admin`, `api`), then
  `putIfAbsent` so concurrent requests for one alias have exactly one winner.
- Aliases share the code space with generated codes; generated codes contain uppercase letters
  or digits only, so a lowercase alias with a hyphen never collides with them in practice.

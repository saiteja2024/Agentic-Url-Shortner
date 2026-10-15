First attempt: add the ttl parameter to `shorten`.

<<<FILE src/main/java/dev/linkkit/Link.java
package dev.linkkit;

import java.time.Instant;

/** A short link; {@code expiresAt} is null for links that never expire. */
public record Link(String code, String url, Instant createdAt, Instant expiresAt) {}
>>>

<<<FILE src/main/java/dev/linkkit/LinkStore.java
package dev.linkkit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory link store with optional expiry. */
public final class LinkStore {

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private final Clock clock;

    public LinkStore(Clock clock) {
        this.clock = clock;
    }

    public Link shorten(String url, Duration ttl) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url must be http(s)");
        }
        Instant now = clock.instant();
        String code = Base62.encode(sequence.incrementAndGet());
        Link link = new Link(code, url, now, ttl == null ? null : now.plus(ttl));
        links.put(code, link);
        return link;
    }

    public Optional<Link> resolve(String code) {
        Link link = links.get(code);
        if (link == null || (link.expiresAt() != null && !clock.instant().isBefore(link.expiresAt()))) {
            return Optional.empty();
        }
        return Optional.of(link);
    }

    public int size() {
        return links.size();
    }
}
>>>

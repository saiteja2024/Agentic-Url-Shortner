package dev.linkkit;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory link store: shortens URLs to sequential Base62 codes and resolves them. */
public final class LinkStore {

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private final Clock clock;

    public LinkStore(Clock clock) {
        this.clock = clock;
    }

    public Link shorten(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url must be http(s)");
        }
        String code = Base62.encode(sequence.incrementAndGet());
        Link link = new Link(code, url, clock.instant());
        links.put(code, link);
        return link;
    }

    public Optional<Link> resolve(String code) {
        return Optional.ofNullable(links.get(code));
    }

    public int size() {
        return links.size();
    }
}

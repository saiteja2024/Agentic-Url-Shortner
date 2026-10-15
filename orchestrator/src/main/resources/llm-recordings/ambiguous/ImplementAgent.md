<<<FILE src/main/java/dev/linkkit/LinkStore.java
package dev.linkkit;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** In-memory link store: sequential Base62 codes and vanity aliases. */
public final class LinkStore {

    private static final Pattern ALIAS = Pattern.compile("[a-z0-9-]{3,32}");
    private static final Set<String> RESERVED = Set.of("admin", "api");

    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private final Clock clock;

    public LinkStore(Clock clock) {
        this.clock = clock;
    }

    public Link shorten(String url) {
        validateUrl(url);
        String code = Base62.encode(sequence.incrementAndGet());
        Link link = new Link(code, url, clock.instant());
        links.put(code, link);
        return link;
    }

    public Link shortenWithAlias(String url, String alias) {
        validateUrl(url);
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("alias must match [a-z0-9-]{3,32}");
        }
        if (RESERVED.contains(alias)) {
            throw new IllegalArgumentException("alias '" + alias + "' is reserved");
        }
        Link link = new Link(alias, url, clock.instant());
        if (links.putIfAbsent(alias, link) != null) {
            throw new IllegalArgumentException("alias '" + alias + "' is taken");
        }
        return link;
    }

    public Optional<Link> resolve(String code) {
        return Optional.ofNullable(links.get(code));
    }

    public int size() {
        return links.size();
    }

    private static void validateUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url must be http(s)");
        }
    }
}
>>>

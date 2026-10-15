Expiry tests.

<<<FILE src/test/java/dev/linkkit/ExpiryTest.java
package dev.linkkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ExpiryTest {

    private final LinkStore store = new LinkStore(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void expiredLinkDoesNotResolve() {
        Link link = store.shorten("https://example.org/sale", Duration.ofHours(1));
        // Misreads REQ-002: expects the link to be expired immediately after creation.
        assertTrue(store.resolve(link.code()).isEmpty());
    }
}
>>>

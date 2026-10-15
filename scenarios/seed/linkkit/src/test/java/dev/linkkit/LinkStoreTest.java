package dev.linkkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LinkStoreTest {

    private final LinkStore store = new LinkStore(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void shortensAndResolves() {
        Link link = store.shorten("https://example.org/a");
        assertEquals("https://example.org/a", store.resolve(link.code()).orElseThrow().url());
        assertEquals(1, store.size());
    }

    @Test
    void unknownCodeIsEmpty() {
        assertTrue(store.resolve("nope").isEmpty());
    }

    @Test
    void rejectsNonHttpUrls() {
        assertThrows(IllegalArgumentException.class, () -> store.shorten("ftp://example.org"));
    }
}

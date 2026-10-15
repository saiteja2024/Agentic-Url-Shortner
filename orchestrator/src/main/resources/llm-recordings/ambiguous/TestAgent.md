<<<FILE src/test/java/dev/linkkit/AliasTest.java
package dev.linkkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class AliasTest {

    private final LinkStore store = new LinkStore(Clock.systemUTC());

    @Test
    void req001AliasBecomesCode() {
        Link link = store.shortenWithAlias("https://example.org/sale", "spring-sale");
        assertEquals("spring-sale", link.code());
        assertEquals("https://example.org/sale", store.resolve("spring-sale").orElseThrow().url());
    }

    @Test
    void req002RejectsTakenAndInvalidAliases() {
        store.shortenWithAlias("https://example.org/a", "taken");
        assertThrows(IllegalArgumentException.class, () -> store.shortenWithAlias("https://example.org/b", "taken"));
        assertThrows(IllegalArgumentException.class, () -> store.shortenWithAlias("https://example.org/b", "Bad Alias"));
        assertThrows(IllegalArgumentException.class, () -> store.shortenWithAlias("https://example.org/b", null));
        assertThrows(IllegalArgumentException.class, () -> store.shortenWithAlias("ftp://x", "valid-alias"));
    }

    @Test
    void req003RejectsReservedWords() {
        assertThrows(IllegalArgumentException.class, () -> store.shortenWithAlias("https://example.org/a", "admin"));
        assertThrows(IllegalArgumentException.class, () -> store.shortenWithAlias("https://example.org/a", "api"));
    }
}
>>>

Tests for the plan above.

<<<FILE src/test/java/dev/linkkit/ClickStatsTest.java
package dev.linkkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClickStatsTest {

    private final ClickStats stats = new ClickStats();

    @Test
    void req001RecordsReferrersAndDirect() {
        stats.record("abc", "https://a.example");
        stats.record("abc", null);
        stats.record("abc", " ");
        assertEquals(3, stats.total("abc"));
        assertEquals(Map.entry(ClickStats.DIRECT, 2L), stats.topReferrers("abc", 1).get(0));
        assertThrows(NullPointerException.class, () -> stats.record(null, "x"));
    }

    @Test
    void req002OrdersByCountThenName() {
        stats.record("abc", "b");
        stats.record("abc", "a");
        stats.record("abc", "c");
        stats.record("abc", "c");
        List<Map.Entry<String, Long>> top = stats.topReferrers("abc", 2);
        assertEquals(List.of(Map.entry("c", 2L), Map.entry("a", 1L)), top);
        assertTrue(stats.topReferrers("unknown", 5).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> stats.topReferrers("abc", 0));
    }

    @Test
    void req003TotalsForUnknownCodeAreZero() {
        assertEquals(0, stats.total("nope"));
    }
}
>>>

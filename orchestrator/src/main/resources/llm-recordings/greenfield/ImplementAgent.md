Implementing REQ-001..REQ-003 as designed.

<<<FILE src/main/java/dev/linkkit/ClickStats.java
package dev.linkkit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Click analytics per short code (REQ-001..REQ-003). */
public final class ClickStats {

    public static final String DIRECT = "(direct)";

    private final Map<String, Map<String, LongAdder>> clicks = new ConcurrentHashMap<>();

    public void record(String code, String referrer) {
        Objects.requireNonNull(code, "code");
        String key = referrer == null || referrer.isBlank() ? DIRECT : referrer;
        clicks.computeIfAbsent(code, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new LongAdder())
                .increment();
    }

    public long total(String code) {
        return clicks.getOrDefault(code, Map.of()).values().stream().mapToLong(LongAdder::sum).sum();
    }

    public List<Map.Entry<String, Long>> topReferrers(String code, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1");
        }
        return clicks.getOrDefault(code, Map.of()).entrySet().stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .toList();
    }
}
>>>

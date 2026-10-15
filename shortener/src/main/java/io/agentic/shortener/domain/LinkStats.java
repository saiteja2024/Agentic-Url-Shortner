package io.agentic.shortener.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;

/** Click analytics for one link (SHORT-010). */
public record LinkStats(
        ShortUrl url,
        boolean expired,
        long totalClicks,
        @Nullable Instant lastClickedAt,
        List<ReferrerClicks> topReferrers) {}

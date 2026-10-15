package io.agentic.shortener.persistence;

import io.agentic.shortener.domain.ReferrerClicks;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class ClickRepository {

    /** Total clicks and the latest click time for one link. */
    public record ClickSummary(long total, @Nullable Instant lastClickedAt) {}

    private final JdbcClient jdbc;

    public ClickRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long urlId, Instant clickedAt, @Nullable String referrer) {
        jdbc.sql("INSERT INTO shortener.clicks (url_id, clicked_at, referrer) VALUES (?, ?, ?)")
                .params(urlId, Timestamp.from(clickedAt), referrer)
                .update();
    }

    public ClickSummary summarize(long urlId) {
        return jdbc.sql("SELECT count(*) AS total, max(clicked_at) AS last FROM shortener.clicks WHERE url_id = ?")
                .param(urlId)
                .query((rs, row) -> {
                    Timestamp last = rs.getTimestamp("last");
                    return new ClickSummary(rs.getLong("total"), last == null ? null : last.toInstant());
                })
                .single();
    }

    /** Referrers by click count, descending; ties broken alphabetically for stable output. */
    public List<ReferrerClicks> topReferrers(long urlId, int limit) {
        return jdbc.sql(
                        "SELECT coalesce(referrer, ?) AS referrer, count(*) AS clicks FROM shortener.clicks WHERE url_id = ?"
                                + " GROUP BY 1 ORDER BY clicks DESC, referrer ASC LIMIT ?")
                .params(ReferrerClicks.DIRECT, urlId, limit)
                .query((rs, row) -> new ReferrerClicks(rs.getString("referrer"), rs.getLong("clicks")))
                .list();
    }
}

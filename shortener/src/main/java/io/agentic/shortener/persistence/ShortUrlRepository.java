package io.agentic.shortener.persistence;

import io.agentic.shortener.domain.ShortUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class ShortUrlRepository {

    private static final String COLUMNS = "id, code, long_url, created_at, expires_at";

    private final JdbcClient jdbc;

    public ShortUrlRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the link unless the code is taken. The unique constraint on {@code code} decides races
     * atomically, so a collision is an empty result rather than an exception (SHORT-002, SHORT-005).
     */
    public Optional<ShortUrl> insertIfAbsent(
            String code, String longUrl, Instant createdAt, @Nullable Instant expiresAt) {
        return jdbc.sql("INSERT INTO shortener.urls (code, long_url, created_at, expires_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (code) DO NOTHING RETURNING " + COLUMNS)
                .params(code, longUrl, Timestamp.from(createdAt), expiresAt == null ? null : Timestamp.from(expiresAt))
                .query(ShortUrlRepository::map)
                .optional();
    }

    public Optional<ShortUrl> findByCode(String code) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM shortener.urls WHERE code = ?")
                .param(code)
                .query(ShortUrlRepository::map)
                .optional();
    }

    private static ShortUrl map(ResultSet rs, int row) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new ShortUrl(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("long_url"),
                rs.getTimestamp("created_at").toInstant(),
                expires == null ? null : expires.toInstant());
    }
}

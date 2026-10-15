package io.agentic.shortener.domain;

import java.time.Instant;
import org.springframework.lang.Nullable;

/** A stored short link. {@code expiresAt} is null for links that never expire. */
public record ShortUrl(long id, String code, String longUrl, Instant createdAt, @Nullable Instant expiresAt) {

    /** A link is expired from the instant {@code expiresAt} is reached. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}

package io.agentic.shortener.web;

import io.agentic.shortener.config.ShortenerProperties;
import io.agentic.shortener.domain.LinkStats;
import io.agentic.shortener.domain.ShortUrl;
import io.agentic.shortener.generated.api.UrlsApi;
import io.agentic.shortener.generated.model.CreateUrlRequest;
import io.agentic.shortener.generated.model.ReferrerCount;
import io.agentic.shortener.generated.model.UrlResponse;
import io.agentic.shortener.generated.model.UrlStats;
import io.agentic.shortener.service.ShortUrlService;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RestController;

/** Implements the generated contract for /api/v1/urls; signatures come from openapi.yaml. */
@RestController
class UrlsController implements UrlsApi {

    private final ShortUrlService service;
    private final ShortenerProperties properties;

    UrlsController(ShortUrlService service, ShortenerProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<UrlResponse> createShortUrl(CreateUrlRequest request) {
        Instant expiresAt =
                request.getExpiresAt() == null ? null : request.getExpiresAt().toInstant();
        ShortUrl url = service.create(request.getUrl(), request.getAlias(), expiresAt);

        UrlResponse body = new UrlResponse(url.code(), shortUrlFor(url.code()), url.longUrl(), utc(url.createdAt()))
                .expiresAt(utc(url.expiresAt()));
        return ResponseEntity.created(URI.create("/api/v1/urls/" + url.code() + "/stats"))
                .body(body);
    }

    @Override
    public ResponseEntity<UrlStats> getUrlStats(String code) {
        LinkStats stats = service.stats(code);
        ShortUrl url = stats.url();

        UrlStats body = new UrlStats(
                        url.code(),
                        url.longUrl(),
                        utc(url.createdAt()),
                        stats.expired(),
                        stats.totalClicks(),
                        stats.topReferrers().stream()
                                .map(r -> new ReferrerCount(r.referrer(), r.clicks()))
                                .toList())
                .expiresAt(utc(url.expiresAt()))
                .lastClickedAt(utc(stats.lastClickedAt()));
        return ResponseEntity.ok(body);
    }

    private String shortUrlFor(String code) {
        String base = properties.baseUrl();
        return (base.endsWith("/") ? base : base + "/") + code;
    }

    @Nullable
    private static OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}

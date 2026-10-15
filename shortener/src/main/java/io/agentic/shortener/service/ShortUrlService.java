package io.agentic.shortener.service;

import io.agentic.shortener.code.CodeGenerator;
import io.agentic.shortener.config.ShortenerProperties;
import io.agentic.shortener.domain.AliasTakenException;
import io.agentic.shortener.domain.CodeSpaceExhaustedException;
import io.agentic.shortener.domain.InvalidFieldException;
import io.agentic.shortener.domain.LinkExpiredException;
import io.agentic.shortener.domain.LinkStats;
import io.agentic.shortener.domain.ShortUrl;
import io.agentic.shortener.domain.ShortUrlNotFoundException;
import io.agentic.shortener.persistence.ClickRepository;
import io.agentic.shortener.persistence.ClickRepository.ClickSummary;
import io.agentic.shortener.persistence.ShortUrlRepository;
import io.agentic.shortener.validation.UrlSafetyValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Create, resolve and report on short links (specs/shortener/requirements.md). */
@Service
public class ShortUrlService {

    static final int MAX_REFERRER_LENGTH = 2048;
    static final int TOP_REFERRERS = 10;

    private static final Logger log = LoggerFactory.getLogger(ShortUrlService.class);

    private final ShortUrlRepository urls;
    private final ClickRepository clicks;
    private final UrlSafetyValidator urlValidator;
    private final CodeGenerator codes;
    private final ShortenerProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Counter collisions;
    private final Counter exhausted;
    private final Counter clickFailures;

    public ShortUrlService(
            ShortUrlRepository urls,
            ClickRepository clicks,
            UrlSafetyValidator urlValidator,
            CodeGenerator codes,
            ShortenerProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.urls = urls;
        this.clicks = clicks;
        this.urlValidator = urlValidator;
        this.codes = codes;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        this.collisions = Counter.builder("shortener.code.collisions")
                .description("Generated codes that were already taken (SHORT-002)")
                .register(meters);
        this.exhausted = Counter.builder("shortener.code.exhausted")
                .description("Creates that failed after every attempt collided (SHORT-002)")
                .register(meters);
        // Pre-register labelled counters at zero so dashboards and rate() work before the first event.
        for (String outcome : new String[] {"found", "not_found", "expired"}) {
            meters.counter("shortener.redirects", "outcome", outcome);
        }
        for (String kind : new String[] {"generated", "alias"}) {
            meters.counter("shortener.urls.issued", "kind", kind);
        }
        this.clickFailures = Counter.builder("shortener.click.record.failures")
                .description("Clicks that could not be stored; the redirect still succeeded (SHORT-007)")
                .register(meters);
    }

    /**
     * Creates a short link with the given alias or a generated code.
     *
     * @throws InvalidFieldException unsafe URL, reserved alias, or expiry not in the future (400)
     * @throws AliasTakenException alias already in use (409)
     * @throws CodeSpaceExhaustedException every generated code collided (503)
     */
    public ShortUrl create(String longUrl, @Nullable String alias, @Nullable Instant expiresAt) {
        urlValidator.validate(longUrl);
        // Postgres stores microseconds; truncate so the response matches what is persisted.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new InvalidFieldException("expiresAt", "must be in the future");
        }
        ShortUrl created = alias != null
                ? createWithAlias(alias, longUrl, now, expiresAt)
                : createWithGeneratedCode(longUrl, now, expiresAt);
        meters.counter("shortener.urls.issued", "kind", alias != null ? "alias" : "generated")
                .increment();
        return created;
    }

    private ShortUrl createWithAlias(String alias, String longUrl, Instant now, @Nullable Instant expiresAt) {
        if (properties.reservedAliases().contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidFieldException("alias", "is reserved");
        }
        return urls.insertIfAbsent(alias, longUrl, now, expiresAt).orElseThrow(() -> new AliasTakenException(alias));
    }

    private ShortUrl createWithGeneratedCode(String longUrl, Instant now, @Nullable Instant expiresAt) {
        int attempts = properties.maxCreateAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String code = codes.next();
            Optional<ShortUrl> inserted = urls.insertIfAbsent(code, longUrl, now, expiresAt);
            if (inserted.isPresent()) {
                return inserted.get();
            }
            collisions.increment();
            log.info("Code collision on attempt {}/{}", attempt, attempts);
        }
        exhausted.increment();
        log.error("Could not allocate a unique code after {} attempts", attempts);
        throw new CodeSpaceExhaustedException(attempts);
    }

    /**
     * Resolves a code for redirect and records the click. Recording is best effort: a storage failure
     * is logged and counted, but never fails the redirect (SHORT-007).
     *
     * @throws ShortUrlNotFoundException unknown code (404)
     * @throws LinkExpiredException expired link (410)
     */
    public ShortUrl resolveAndRecordClick(String code, @Nullable String referrer) {
        Instant now = clock.instant();
        ShortUrl url = urls.findByCode(code).orElseThrow(() -> {
            meters.counter("shortener.redirects", "outcome", "not_found").increment();
            return new ShortUrlNotFoundException(code);
        });
        if (url.isExpiredAt(now)) {
            meters.counter("shortener.redirects", "outcome", "expired").increment();
            throw new LinkExpiredException(code);
        }
        try {
            clicks.record(url.id(), now, normaliseReferrer(referrer));
        } catch (DataAccessException e) {
            clickFailures.increment();
            log.warn("Failed to record click for code {}; redirecting anyway", code, e);
        }
        meters.counter("shortener.redirects", "outcome", "found").increment();
        return url;
    }

    /**
     * Reads every figure from one snapshot, so a click committed mid-read is counted everywhere or
     * nowhere (SHORT-010). READ_COMMITTED would let the two aggregate queries disagree.
     *
     * @throws ShortUrlNotFoundException unknown code (404); expired links still report (A4)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LinkStats stats(String code) {
        ShortUrl url = urls.findByCode(code).orElseThrow(() -> new ShortUrlNotFoundException(code));
        ClickSummary summary = clicks.summarize(url.id());
        return new LinkStats(
                url,
                url.isExpiredAt(clock.instant()),
                summary.total(),
                summary.lastClickedAt(),
                clicks.topReferrers(url.id(), TOP_REFERRERS));
    }

    @Nullable
    private static String normaliseReferrer(@Nullable String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        return referrer.length() > MAX_REFERRER_LENGTH ? referrer.substring(0, MAX_REFERRER_LENGTH) : referrer;
    }
}

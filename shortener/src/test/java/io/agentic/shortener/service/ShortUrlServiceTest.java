package io.agentic.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentic.shortener.code.CodeGenerator;
import io.agentic.shortener.config.ShortenerProperties;
import io.agentic.shortener.domain.AliasTakenException;
import io.agentic.shortener.domain.CodeSpaceExhaustedException;
import io.agentic.shortener.domain.InvalidFieldException;
import io.agentic.shortener.domain.LinkExpiredException;
import io.agentic.shortener.domain.ShortUrl;
import io.agentic.shortener.domain.ShortUrlNotFoundException;
import io.agentic.shortener.persistence.ClickRepository;
import io.agentic.shortener.persistence.ShortUrlRepository;
import io.agentic.shortener.validation.UrlSafetyValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ShortUrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String LONG_URL = "https://www.example.org/page";

    private final ShortUrlRepository urls = mock(ShortUrlRepository.class);
    private final ClickRepository clicks = mock(ClickRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Deque<String> nextCodes = new ArrayDeque<>();
    private final CodeGenerator codes = nextCodes::pop;
    private final ShortUrlService service = new ShortUrlService(
            urls,
            clicks,
            new UrlSafetyValidator(host -> List.of(InetAddress.getByName("93.184.215.14"))),
            codes,
            new ShortenerProperties("http://sho.rt", 7, 5, Set.of("api", "actuator", "health")),
            Clock.fixed(NOW, ZoneOffset.UTC),
            meters);

    @Test
    @Tag("SHORT-002")
    @DisplayName("SHORT-002: retries with a new code when the generated one is taken")
    void retriesOnCollision() {
        nextCodes.addAll(List.of("taken01", "taken02", "fresh03"));
        when(urls.insertIfAbsent(eq("taken01"), anyString(), any(), any())).thenReturn(Optional.empty());
        when(urls.insertIfAbsent(eq("taken02"), anyString(), any(), any())).thenReturn(Optional.empty());
        when(urls.insertIfAbsent(eq("fresh03"), anyString(), any(), any()))
                .thenReturn(Optional.of(link("fresh03", null)));

        ShortUrl created = service.create(LONG_URL, null, null);

        assertThat(created.code()).isEqualTo("fresh03");
        assertThat(meters.counter("shortener.code.collisions").count()).isEqualTo(2);
    }

    @Test
    @Tag("SHORT-002")
    @DisplayName("SHORT-002: gives up after 5 colliding attempts with a 503-mapped error")
    void givesUpAfterMaxAttempts() {
        nextCodes.addAll(List.of("c000001", "c000002", "c000003", "c000004", "c000005", "c000006"));
        when(urls.insertIfAbsent(anyString(), anyString(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(LONG_URL, null, null))
                .isInstanceOf(CodeSpaceExhaustedException.class)
                .hasMessageContaining("5");
        verify(urls, times(5)).insertIfAbsent(anyString(), anyString(), any(), any());
        assertThat(meters.counter("shortener.code.exhausted").count()).isEqualTo(1);
    }

    @Test
    @Tag("SHORT-003")
    @DisplayName("SHORT-003: a taken alias is a conflict, never retried")
    void takenAliasIsConflict() {
        when(urls.insertIfAbsent(eq("launch"), anyString(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(LONG_URL, "launch", null)).isInstanceOf(AliasTakenException.class);
        verify(urls, times(1)).insertIfAbsent(anyString(), anyString(), any(), any());
    }

    @Test
    @Tag("SHORT-003")
    @DisplayName("SHORT-003: reserved words are rejected as aliases, case-insensitively")
    void reservedAliasRejected() {
        assertThatThrownBy(() -> service.create(LONG_URL, "Actuator", null))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class, e -> assertThat(e.field()).isEqualTo("alias"));
        verify(urls, never()).insertIfAbsent(anyString(), anyString(), any(), any());
    }

    @Test
    @Tag("SHORT-004")
    @DisplayName("SHORT-004: expiry must be strictly in the future")
    void expiryMustBeInFuture() {
        assertThatThrownBy(() -> service.create(LONG_URL, null, NOW))
                .isInstanceOfSatisfying(
                        InvalidFieldException.class, e -> assertThat(e.field()).isEqualTo("expiresAt"));
        assertThatThrownBy(() -> service.create(LONG_URL, null, NOW.minusSeconds(1)))
                .isInstanceOf(InvalidFieldException.class);
    }

    @Test
    @Tag("SHORT-011")
    @DisplayName("SHORT-011: unsafe URLs are rejected before anything is persisted")
    void unsafeUrlNeverPersisted() {
        assertThatThrownBy(() -> service.create("javascript:alert(1)", null, null))
                .isInstanceOf(InvalidFieldException.class);
        verify(urls, never()).insertIfAbsent(anyString(), anyString(), any(), any());
    }

    @Test
    @Tag("SHORT-007")
    @DisplayName("SHORT-007: a failure to record the click does not fail the redirect")
    void clickFailureDoesNotFailRedirect() {
        when(urls.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234", null)));
        doThrow(new DataAccessResourceFailureException("db down")).when(clicks).record(anyLong(), any(), any());

        ShortUrl resolved = service.resolveAndRecordClick("abc1234", "https://news.example.com/");

        assertThat(resolved.longUrl()).isEqualTo(LONG_URL);
        assertThat(meters.counter("shortener.click.record.failures").count()).isEqualTo(1);
    }

    @Test
    @Tag("SHORT-007")
    @DisplayName("SHORT-007: referrers are truncated to 2048 chars and blank means direct")
    void normalisesReferrer() {
        when(urls.findByCode("abc1234")).thenReturn(Optional.of(link("abc1234", null)));

        service.resolveAndRecordClick("abc1234", "https://r.example/" + "x".repeat(3000));
        service.resolveAndRecordClick("abc1234", "   ");

        verify(clicks)
                .record(eq(1L), eq(NOW), org.mockito.ArgumentMatchers.argThat(r -> r != null && r.length() == 2048));
        verify(clicks).record(eq(1L), eq(NOW), isNull());
    }

    @Test
    @Tag("SHORT-008")
    @DisplayName("SHORT-008: unknown codes are not found and record nothing")
    void unknownCode() {
        when(urls.findByCode("nope123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveAndRecordClick("nope123", null))
                .isInstanceOf(ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> service.stats("nope123")).isInstanceOf(ShortUrlNotFoundException.class);
        verify(clicks, never()).record(anyLong(), any(), any());
    }

    @Test
    @Tag("SHORT-009")
    @DisplayName("SHORT-009: expired links are gone and record nothing")
    void expiredLink() {
        when(urls.findByCode("old1234")).thenReturn(Optional.of(link("old1234", NOW)));

        assertThatThrownBy(() -> service.resolveAndRecordClick("old1234", null))
                .isInstanceOf(LinkExpiredException.class);
        verify(clicks, never()).record(anyLong(), any(), any());
        assertThat(meters.counter("shortener.redirects", "outcome", "expired").count())
                .isEqualTo(1);
    }

    private static ShortUrl link(String code, Instant expiresAt) {
        return new ShortUrl(1L, code, LONG_URL, NOW.minusSeconds(3600), expiresAt);
    }
}

package io.agentic.shortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param baseUrl public base used to build {@code shortUrl}
 * @param codeLength generated code length (SHORT-001)
 * @param maxCreateAttempts collision retries before giving up (SHORT-002)
 * @param reservedAliases aliases that would shadow service paths (SHORT-003), compared case-insensitively
 */
@Validated
@ConfigurationProperties("shortener")
public record ShortenerProperties(
        @NotBlank String baseUrl,
        @Min(3) @Max(32) int codeLength,
        @Min(1) @Max(20) int maxCreateAttempts,
        Set<String> reservedAliases) {

    public ShortenerProperties {
        reservedAliases = reservedAliases == null ? Set.of() : Set.copyOf(reservedAliases);
    }
}

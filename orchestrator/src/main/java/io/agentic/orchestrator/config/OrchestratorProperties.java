package io.agentic.orchestrator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param pipelineLocation Spring resource holding the pipeline YAML (copied from specs/orchestrator)
 * @param maxParallelNodes size of the bounded pool nodes run on (ORCH-005)
 * @param apiKey shared secret for mutating endpoints (ORCH-037); empty = the key is not accepted
 * @param security OAuth2 bearer-token settings (ORCH-038)
 * @param workspacesDir where per-run git working copies are created (ORCH-022)
 * @param targetProject the project runs work on (copied per run)
 * @param commandTimeout limit for each command gate (ORCH-030)
 * @param tokenBudget default LLM token budget per run (ORCH-035)
 * @param timeBudget default wall-clock budget per run (ORCH-035)
 */
@Validated
@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(
        @NotBlank String pipelineLocation,
        @Min(1) @Max(64) int maxParallelNodes,
        String apiKey,
        @Valid Security security,
        @NotBlank String workspacesDir,
        @NotBlank String targetProject,
        @NotNull Duration commandTimeout,
        @Min(1) long tokenBudget,
        @NotNull Duration timeBudget,
        @Valid @NotNull Llm llm) {

    public OrchestratorProperties {
        security = security == null ? new Security(null) : security;
    }

    public record Security(@Valid Jwt jwt) {
        public Security {
            jwt = jwt == null ? new Jwt(null, null, null, null) : jwt;
        }
    }

    /**
     * Exactly one source of keys is used, in this order: {@code jwkSetUri}, {@code issuerUri}
     * (discovery), {@code hmacSecret}. {@code issuerUri} and {@code audience} are also enforced as
     * claims when set.
     */
    public record Jwt(String issuerUri, String jwkSetUri, String hmacSecret, String audience) {

        public boolean enabled() {
            return hasText(issuerUri) || hasText(jwkSetUri) || hasText(hmacSecret);
        }

        public static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    /** @param mode {@code mock} (recorded responses) or {@code claude} */
    public record Llm(@NotBlank String mode, @NotBlank String model, String apiKey, @NotBlank String recordings) {}
}

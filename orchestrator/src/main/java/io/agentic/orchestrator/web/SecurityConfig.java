package io.agentic.orchestrator.web;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.HEAD;
import static org.springframework.http.HttpMethod.OPTIONS;
import static org.springframework.http.HttpMethod.POST;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentic.orchestrator.config.OrchestratorProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may change what (ORCH-037, ORCH-038). Reads stay open for the console and dashboards.
 * Mutations need {@code runs:write} or {@code approvals:write}, granted by an OAuth2 JWT's scopes
 * or, for both, by the shared {@code X-API-Key}. Stateless: no sessions, so no CSRF surface.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String RUNS_WRITE = "SCOPE_runs:write";
    static final String APPROVALS_WRITE = "SCOPE_approvals:write";
    private static final int MIN_HMAC_BYTES = 32;
    private static final JwtGrantedAuthoritiesConverter SCOPES = new JwtGrantedAuthoritiesConverter();

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, OrchestratorProperties properties, ObjectMapper json)
            throws Exception {
        ProblemResponses problems = new ProblemResponses(json);
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rules -> rules.requestMatchers(
                                POST, "/api/v1/runs", "/api/v1/runs/*/kill", "/api/v1/runs/*/nodes/*/replan")
                        .hasAuthority(RUNS_WRITE)
                        .requestMatchers(
                                POST,
                                "/api/v1/runs/*/nodes/*/approve",
                                "/api/v1/runs/*/nodes/*/reject",
                                "/api/v1/runs/*/nodes/*/answers")
                        .hasAuthority(APPROVALS_WRITE)
                        .requestMatchers(GET)
                        .permitAll()
                        .requestMatchers(HEAD)
                        .permitAll()
                        .requestMatchers(OPTIONS)
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(properties.apiKey(), problems),
                        BearerTokenAuthenticationFilter.class);
        Optional<JwtDecoder> decoder = jwtDecoder(properties.security().jwt());
        if (decoder.isPresent()) {
            http.oauth2ResourceServer(server -> server.jwt(jwt ->
                            jwt.decoder(decoder.get()).jwtAuthenticationConverter(SecurityConfig::authentication))
                    .authenticationEntryPoint(problems)
                    .accessDeniedHandler(problems));
        }
        return http.build();
    }

    /** Keys from a JWK set, OIDC discovery, or a shared HS256 secret; empty when JWT is off. */
    static Optional<JwtDecoder> jwtDecoder(OrchestratorProperties.Jwt config) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        NimbusJwtDecoder decoder;
        if (OrchestratorProperties.Jwt.hasText(config.jwkSetUri())) {
            decoder = NimbusJwtDecoder.withJwkSetUri(config.jwkSetUri()).build();
        } else if (OrchestratorProperties.Jwt.hasText(config.issuerUri())) {
            decoder = NimbusJwtDecoder.withIssuerLocation(config.issuerUri()).build();
        } else {
            byte[] secret = config.hmacSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < MIN_HMAC_BYTES) {
                throw new IllegalStateException(
                        "orchestrator.security.jwt.hmac-secret must be at least " + MIN_HMAC_BYTES + " bytes");
            }
            decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        }
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        if (OrchestratorProperties.Jwt.hasText(config.issuerUri())) {
            validators.add(new JwtIssuerValidator(config.issuerUri()));
        }
        if (OrchestratorProperties.Jwt.hasText(config.audience())) {
            validators.add(new JwtClaimValidator<List<String>>(
                    JwtClaimNames.AUD, aud -> aud != null && aud.contains(config.audience())));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return Optional.of(decoder);
    }

    /** Scopes become {@code SCOPE_*} authorities; the name is the user, used as the decider. */
    static AbstractAuthenticationToken authentication(Jwt jwt) {
        String name =
                jwt.hasClaim("preferred_username") ? jwt.getClaimAsString("preferred_username") : jwt.getSubject();
        return new JwtAuthenticationToken(jwt, SCOPES.convert(jwt), name == null ? "unknown" : name);
    }
}

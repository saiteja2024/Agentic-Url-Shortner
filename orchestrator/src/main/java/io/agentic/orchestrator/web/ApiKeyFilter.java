package io.agentic.orchestrator.web;

import io.agentic.orchestrator.config.OrchestratorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ORCH-037: every mutating API call needs {@code X-API-Key}. Reads stay open for the UI and dashboards.
 * Fails closed: with no key configured, all mutations are refused.
 */
@Component
class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final byte[] expected;

    ApiKeyFilter(OrchestratorProperties properties) {
        String key = properties.apiKey() == null ? "" : properties.apiKey();
        this.expected = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        boolean valid = expected.length > 0
                && given != null
                && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
        if (valid) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String detail = expected.length == 0
                ? "No API key is configured; mutations are disabled."
                : "Missing or invalid X-API-Key.";
        response.getWriter()
                .write("{\"type\":\"https://agentic.local/problems/unauthorized\",\"title\":\"Unauthorized\","
                        + "\"status\":401,\"detail\":\"" + detail + "\",\"instance\":\"" + request.getRequestURI()
                        + "\"}");
    }
}

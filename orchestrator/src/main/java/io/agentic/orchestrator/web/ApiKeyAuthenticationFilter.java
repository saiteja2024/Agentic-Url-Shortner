package io.agentic.orchestrator.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ORCH-037: {@code X-API-Key} authenticates a mutation with both write scopes, so the console and
 * scripts keep working next to OAuth2 tokens (ORCH-038). A wrong key is rejected at once; no key
 * leaves the request to the bearer-token filter. With no key configured, every key is wrong.
 */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    static final String PRINCIPAL = "api-key";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final List<GrantedAuthority> AUTHORITIES = List.of(
            new SimpleGrantedAuthority(SecurityConfig.RUNS_WRITE),
            new SimpleGrantedAuthority(SecurityConfig.APPROVALS_WRITE));

    private final byte[] expected;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    ApiKeyAuthenticationFilter(String apiKey, AuthenticationEntryPoint entryPoint) {
        this.expected = (apiKey == null ? "" : apiKey).getBytes(StandardCharsets.UTF_8);
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (given == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean valid = expected.length > 0 && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            contexts.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Missing or invalid X-API-Key."));
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, AUTHORITIES));
        contexts.setContext(context);
        chain.doFilter(request, response);
    }
}

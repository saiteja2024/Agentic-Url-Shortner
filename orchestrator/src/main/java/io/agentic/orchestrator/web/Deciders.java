package io.agentic.orchestrator.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** ORCH-038: with a bearer token, the token's user is the decider; the body's {@code by} is a claim. */
final class Deciders {

    private Deciders() {}

    static String resolve(String claimed) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken ? authentication.getName() : claimed;
    }
}

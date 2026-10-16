package io.agentic.orchestrator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401 and 403 as RFC 9457 ProblemDetail, like every other error of the API. */
final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    ProblemResponses(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        String challenge = "Bearer";
        String detail = "Authentication required: send X-API-Key or an OAuth2 bearer token.";
        if (e instanceof OAuth2AuthenticationException oauth) {
            challenge = "Bearer error=\"invalid_token\"";
            detail = "Invalid bearer token: " + describe(oauth);
        } else if (request.getHeader(ApiKeyAuthenticationFilter.HEADER) != null) {
            detail = e.getMessage();
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "Unauthorized", detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "forbidden",
                "Forbidden",
                "The token lacks the scope this operation needs (runs:write or approvals:write).");
    }

    private static String describe(OAuth2AuthenticationException e) {
        String description = e.getError().getDescription();
        return description == null ? e.getError().getErrorCode() : description;
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String type,
            String title,
            String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://agentic.local/problems/" + type);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}

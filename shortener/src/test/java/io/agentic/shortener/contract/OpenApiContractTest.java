package io.agentic.shortener.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves the contract harness loads the 3.1 spec and actually catches drift. */
class OpenApiContractTest {

    private static final String STATS_PATH = "/api/v1/urls/aZ3kP9q/stats";

    @Test
    @DisplayName("contract harness accepts a response that matches the spec")
    void acceptsConformingResponse() {
        ValidationReport report = OpenApiContract.validator()
                .validateResponse(
                        STATS_PATH,
                        Request.Method.GET,
                        SimpleResponse.Builder.ok()
                                .withContentType("application/json")
                                .withBody(
                                        """
                                        {"code":"aZ3kP9q","longUrl":"https://www.example.org/a",
                                         "createdAt":"2026-10-15T10:00:00Z","expired":false,
                                         "totalClicks":3,"topReferrers":[{"referrer":"(direct)","clicks":3}]}
                                        """)
                                .build());

        assertThat(report.getMessages()).isEmpty();
    }

    @Test
    @DisplayName("contract harness rejects a response that drifts from the spec")
    void rejectsDriftingResponse() {
        ValidationReport report = OpenApiContract.validator()
                .validateResponse(
                        STATS_PATH,
                        Request.Method.GET,
                        SimpleResponse.Builder.ok()
                                .withContentType("application/json")
                                // missing required fields + undeclared property
                                .withBody("{\"code\":\"aZ3kP9q\",\"clicks\":3}")
                                .build());

        assertThat(report.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("contract harness rejects an undeclared error status")
    void rejectsUndeclaredStatus() {
        ValidationReport report = OpenApiContract.validator()
                .validateResponse(
                        STATS_PATH,
                        Request.Method.GET,
                        SimpleResponse.Builder.status(418)
                                .withContentType("application/problem+json")
                                .withBody("{\"type\":\"about:blank\",\"title\":\"teapot\",\"status\":418}")
                                .build());

        assertThat(report.hasErrors()).isTrue();
    }
}

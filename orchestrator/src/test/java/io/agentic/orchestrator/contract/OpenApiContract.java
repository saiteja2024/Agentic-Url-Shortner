package io.agentic.orchestrator.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Validates MockMvc traffic against the hand-written contract (specs/orchestrator/openapi.yaml,
 * packaged at /static/openapi/openapi.yaml). Usage:
 *
 * <pre>{@code
 * mockMvc.perform(post("/api/v1/urls")...).andExpect(OpenApiContract.conformsToSpec());
 * }</pre>
 */
public final class OpenApiContract {

    static final String SPEC_RESOURCE = "/static/openapi/openapi.yaml";

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            // Inline content avoids file-URI resolution issues with spaces in checkout paths.
            .createForInlineApiSpecification(loadSpec())
            .build();

    /** Same contract, request checks off: for tests that send deliberately invalid requests. */
    private static final OpenApiInteractionValidator RESPONSE_ONLY_VALIDATOR =
            OpenApiInteractionValidator.createForInlineApiSpecification(loadSpec())
                    .withLevelResolver(LevelResolver.create()
                            .withLevel("validation.request", ValidationReport.Level.IGNORE)
                            .build())
                    .build();

    private OpenApiContract() {}

    public static OpenApiInteractionValidator validator() {
        return VALIDATOR;
    }

    /** Fails the test if the request or the response deviates from the OpenAPI contract. */
    public static ResultMatcher conformsToSpec() {
        return OpenApiValidationMatchers.openApi().isValid(VALIDATOR);
    }

    /** Fails the test if the response deviates from the contract; the request may be invalid on purpose. */
    public static ResultMatcher responseConformsToSpec() {
        return OpenApiValidationMatchers.openApi().isValid(RESPONSE_ONLY_VALIDATOR);
    }

    private static String loadSpec() {
        try (InputStream in = OpenApiContract.class.getResourceAsStream(SPEC_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Contract not on classpath: " + SPEC_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

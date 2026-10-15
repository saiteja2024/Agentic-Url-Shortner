package io.agentic.shortener.web;

import static io.agentic.shortener.contract.OpenApiContract.conformsToSpec;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentic.shortener.domain.CodeSpaceExhaustedException;
import io.agentic.shortener.service.ShortUrlService;
import io.agentic.shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Failure paths that can't be provoked through real inputs: the service is replaced by a mock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ServerErrorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService service;

    @Test
    @Tag("SHORT-016")
    @DisplayName("SHORT-016: unexpected errors are a generic 500 problem with no internals")
    void unexpectedErrorIsScrubbed() throws Exception {
        when(service.stats(anyString()))
                .thenThrow(new IllegalStateException("password=hunter2 at org.postgresql.Driver"));

        mockMvc.perform(get("/api/v1/urls/abc1234/stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("postgresql"))));
    }

    @Test
    @Tag("SHORT-002")
    @DisplayName("SHORT-002: exhausted code allocation is a 503 problem that matches the contract")
    void exhaustedIs503() throws Exception {
        when(service.create(anyString(), any(), any())).thenThrow(new CodeSpaceExhaustedException(5));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.example.org/\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(conformsToSpec())
                .andExpect(jsonPath("$.type").value("https://agentic.local/problems/code-allocation-failed"));
    }
}

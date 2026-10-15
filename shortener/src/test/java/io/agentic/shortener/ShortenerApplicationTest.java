package io.agentic.shortener;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentic.shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShortenerApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("context starts, migrates the schema and reports healthy")
    void contextStartsAndReportsHealthy() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("serves the hand-written OpenAPI contract, not a code-generated one")
    void servesHandWrittenContract() throws Exception {
        mockMvc.perform(get("/openapi/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("operationId: createShortUrl")));
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/openapi/springdoc")).andExpect(jsonPath("$.paths").isEmpty());
    }

    @Test
    @DisplayName("Swagger UI is configured to render the hand-written contract")
    void swaggerUiPointsAtHandWrittenContract() throws Exception {
        mockMvc.perform(get("/openapi/springdoc/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi/openapi.yaml"));
    }
}

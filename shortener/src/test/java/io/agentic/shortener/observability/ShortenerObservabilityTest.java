package io.agentic.shortener.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentic.shortener.support.InMemorySpans;
import io.agentic.shortener.support.TestcontainersConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// Boot tests disable metrics export and tracing unless explicitly re-enabled.
@AutoConfigureObservability
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, InMemorySpans.class})
class ShortenerObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemorySpanExporter spans;

    @Test
    @Tag("OBS-001")
    @DisplayName("OBS-001: /actuator/prometheus exposes custom and HTTP metrics tagged with the application")
    void prometheusEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.example.org/obs\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("shortener_urls_issued_total{")))
                .andExpect(content().string(containsString("application=\"shortener\"")))
                .andExpect(content().string(containsString("shortener_code_collisions_total")))
                .andExpect(content().string(containsString("http_server_requests_seconds_count")));
    }

    @Test
    @Tag("OBS-002")
    @DisplayName("OBS-002: requests are traced with the service name and expose the trace id")
    void requestsAreTraced() throws Exception {
        spans.reset();
        String traceId = mockMvc.perform(get("/api/v1/urls/zzNope99/stats"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", matchesPattern("[0-9a-f]{32}")))
                .andReturn()
                .getResponse()
                .getHeader("X-Trace-Id");

        List<SpanData> finished = awaitSpans();
        assertThat(finished).anySatisfy(span -> {
            assertThat(span.getTraceId()).isEqualTo(traceId);
            assertThat(span.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                    .isEqualTo("shortener");
        });
    }

    private List<SpanData> awaitSpans() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (spans.getFinishedSpanItems().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return spans.getFinishedSpanItems();
    }
}

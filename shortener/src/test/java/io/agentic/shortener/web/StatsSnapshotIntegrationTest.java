package io.agentic.shortener.web;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.agentic.shortener.persistence.ClickRepository;
import io.agentic.shortener.support.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Stats must be read from one snapshot even when a click commits mid-read (SHORT-010). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatsSnapshotIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ClickRepository clicks;

    @Test
    @Tag("SHORT-010")
    @DisplayName("SHORT-010: a click committed between the stats reads is counted in all figures or none")
    void statsComeFromOneSnapshot() throws Exception {
        String body = mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.example.org/" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String code = JsonPath.read(body, "$.code");

        // Right after the summary query, commit a click from another thread (its own connection
        // and transaction), so it lands between the summary and the top-referrers query.
        doAnswer(invocation -> {
                    Object summary = invocation.callRealMethod();
                    long urlId = invocation.getArgument(0);
                    CompletableFuture.runAsync(() -> clicks.record(urlId, Instant.now(), "https://late.example/"))
                            .get(10, TimeUnit.SECONDS);
                    return summary;
                })
                .when(clicks)
                .summarize(anyLong());

        mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(0))
                .andExpect(jsonPath("$.topReferrers").isEmpty());
    }
}

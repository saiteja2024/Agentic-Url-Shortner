package io.agentic.orchestrator.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentic.orchestrator.support.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConsolePageTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @Tag("ORCH-050")
    @DisplayName("ORCH-050: the console is served at / and drives the public API with the API key")
    void consoleServed() throws Exception {
        // "/" is Spring's welcome page: a forward to index.html (MockMvc does not follow forwards).
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>SDLC Orchestrator</title>")))
                .andExpect(content().string(containsString("/api/v1/runs")))
                .andExpect(content().string(containsString("X-API-Key")))
                .andExpect(content().string(containsString("/approve")))
                .andExpect(content().string(containsString("/answers")))
                .andExpect(content().string(containsString("/kill")))
                .andExpect(content().string(containsString("/replan")))
                .andExpect(content().string(containsString("/report")))
                .andExpect(content().string(containsString("/events")));
    }
}

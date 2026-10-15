package io.agentic.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentic.orchestrator.pipeline.PipelineDefinition;
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
class OrchestratorApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PipelineDefinition pipeline;

    @Test
    @DisplayName("context starts, migrates the schema and reports healthy")
    void contextStartsAndReportsHealthy() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @Tag("ORCH-001")
    @DisplayName("ORCH-001: the orchestrator boots with the stages from pipeline.yaml")
    void bootsWithPipelineFromSpec() {
        assertThat(pipeline.metadata().name()).isEqualTo("sdlc-default");
        assertThat(pipeline.stages()).hasSize(8);
    }
}

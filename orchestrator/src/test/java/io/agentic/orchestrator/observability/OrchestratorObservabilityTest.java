package io.agentic.orchestrator.observability;

import static io.agentic.orchestrator.support.Stages.pipeline;
import static io.agentic.orchestrator.support.Stages.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentic.orchestrator.dag.DagEngine;
import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.dag.Run;
import io.agentic.orchestrator.dag.RunListener;
import io.agentic.orchestrator.dag.RunMode;
import io.agentic.orchestrator.gate.GateResult;
import io.agentic.orchestrator.support.TestcontainersConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

// Boot tests disable metrics export and tracing unless explicitly re-enabled.
@AutoConfigureObservability
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class OrchestratorObservabilityTest {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorObservabilityTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservationRegistry observations;

    @Test
    @Tag("OBS-001")
    @DisplayName("OBS-001: the orchestrator exposes Prometheus metrics tagged with its application name")
    void prometheusEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("application=\"orchestrator\"")));
    }

    @Test
    @Tag("OBS-003")
    @DisplayName("OBS-003: logs are JSON lines carrying traceId and spanId inside a span")
    void jsonLogsCarryTraceIds(CapturedOutput output) {
        Observation.createNotStarted("obs-test", observations).observe(() -> log.info("inside a span"));

        JsonNode line = jsonLine(output, "inside a span");
        assertThat(line.path("traceId").asText()).matches("[0-9a-f]{32}");
        assertThat(line.path("spanId").asText()).matches("[0-9a-f]{16}");
    }

    @Test
    @Tag("OBS-003")
    @DisplayName("OBS-003: logs written while a DAG node runs carry runId and nodeId")
    void nodeLogsCarryRunAndNodeIds(CapturedOutput output) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            DagEngine engine = new DagEngine(
                    pool,
                    agent -> ctx -> {
                        log.info("agent working");
                        return NodeOutput.empty();
                    },
                    (id, spec) -> ctx -> GateResult.pass(),
                    Clock.systemUTC());
            Run run = engine.start(pipeline(stage("design")), RunMode.GREENFIELD, RunListener.NONE);
            run.completion().get(10, TimeUnit.SECONDS);

            JsonNode line = jsonLine(output, "agent working");
            assertThat(line.path("runId").asText()).isEqualTo(run.id().toString());
            assertThat(line.path("nodeId").asText()).isEqualTo("design");
        } finally {
            pool.shutdownNow();
        }
    }

    private static JsonNode jsonLine(CapturedOutput output, String message) {
        List<String> lines =
                output.getOut().lines().filter(l -> l.contains(message)).toList();
        assertThat(lines).as("log line containing '%s'", message).isNotEmpty();
        try {
            return JSON.readTree(lines.getFirst());
        } catch (Exception e) {
            throw new AssertionError("log line is not JSON: " + lines.getFirst(), e);
        }
    }
}

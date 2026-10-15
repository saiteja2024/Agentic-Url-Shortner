package io.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MockLlmClientTest {

    private final MockLlmClient llm = new MockLlmClient("llm-recordings");

    private static LlmRequest request(String scenario, String agent, int attempt) {
        return new LlmRequest(scenario, agent, "design", attempt, "system", "prompt text of some length");
    }

    @Test
    @Tag("ORCH-020")
    @DisplayName("ORCH-020: replays the attempt-specific recording, then the agent's default")
    void replaysRecordings() {
        assertThat(llm.complete(request("unit", "DesignAgent", 1)).text()).isEqualTo("first attempt\n");
        assertThat(llm.complete(request("unit", "DesignAgent", 2)).text()).isEqualTo("any attempt\n");
    }

    @Test
    @Tag("ORCH-020")
    @DisplayName("ORCH-020: reports token usage and the model, so budgets apply in mock mode too")
    void reportsUsage() {
        LlmResponse response = llm.complete(request("unit", "DesignAgent", 1));
        assertThat(response.inputTokens()).isPositive();
        assertThat(response.outputTokens()).isPositive();
        assertThat(response.model()).isEqualTo("mock");
    }

    @Test
    @Tag("ORCH-020")
    @DisplayName("ORCH-020: a missing recording fails with the key it looked for")
    void missingRecording() {
        assertThatThrownBy(() -> llm.complete(request("unit", "ReleaseAgent", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unit/ReleaseAgent");
        assertThatThrownBy(() -> llm.complete(request("../etc", "x", 1))).isInstanceOf(IllegalArgumentException.class);
    }
}

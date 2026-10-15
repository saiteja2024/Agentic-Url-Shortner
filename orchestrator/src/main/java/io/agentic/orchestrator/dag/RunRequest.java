package io.agentic.orchestrator.dag;

import java.time.Duration;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Parameters of one run.
 *
 * @param scenario recorded-response set for the mock LLM
 * @param tokenBudget maximum LLM tokens (input + output) for the run (ORCH-035)
 * @param timeBudget maximum wall-clock time, or null for none (ORCH-035)
 */
public record RunRequest(
        UUID runId, String scenario, RunMode mode, String request, long tokenBudget, @Nullable Duration timeBudget) {

    public static RunRequest of(RunMode mode) {
        return new RunRequest(UUID.randomUUID(), "test", mode, "test request", Long.MAX_VALUE, null);
    }
}

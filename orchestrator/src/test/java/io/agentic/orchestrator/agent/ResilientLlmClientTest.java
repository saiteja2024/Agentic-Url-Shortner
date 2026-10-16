package io.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ResilientLlmClientTest {

    private static final LlmRequest REQUEST = new LlmRequest("unit", "DesignAgent", "design", 1, "system", "prompt");
    private static final LlmResponse OK = new LlmResponse("done", 10, 5, "test");

    private final AtomicInteger calls = new AtomicInteger();
    private final Deque<Supplier<LlmResponse>> script = new ArrayDeque<>();

    // Small, fast settings; the production values live in application.yml.
    private final CircuitBreaker breaker = CircuitBreaker.of(
            "llm",
            CircuitBreakerConfig.custom()
                    .slidingWindowSize(4)
                    .minimumNumberOfCalls(4)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMillis(200))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .recordExceptions(LlmUnavailableException.class)
                    .build());
    private final Retry retry = Retry.of(
            "llm",
            RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(1))
                    .retryExceptions(LlmUnavailableException.class)
                    .build());

    private final LlmClient delegate = request -> {
        calls.incrementAndGet();
        Supplier<LlmResponse> next = script.poll();
        return next == null ? OK : next.get();
    };
    private final ResilientLlmClient client = new ResilientLlmClient(delegate, breaker, retry);

    private void failTransiently(int times) {
        for (int i = 0; i < times; i++) {
            script.add(() -> {
                throw new LlmUnavailableException("HTTP 529 overloaded", null);
            });
        }
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: transient failures are retried and the call recovers")
    void retriesTransientFailures() {
        failTransiently(2);

        assertThat(client.complete(REQUEST)).isEqualTo(OK);

        assertThat(calls).hasValue(3);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt())
                .isEqualTo(1);
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: retries are bounded; the last transient error surfaces")
    void retriesAreBounded() {
        failTransiently(10);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("529");
        assertThat(calls).hasValue(3);
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: repeated transient failures open the circuit and later calls fail fast")
    void opensCircuit() {
        failTransiently(4);
        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LlmUnavailableException.class);
        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LlmUnavailableException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int before = calls.get();

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("circuit breaker 'llm' is open");
        assertThat(calls).hasValue(before);
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: after the open-state wait, a successful trial call closes the circuit")
    void halfOpenRecovers() throws Exception {
        breaker.transitionToOpenState();
        Thread.sleep(250);

        assertThat(client.complete(REQUEST)).isEqualTo(OK);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: non-transient errors are not retried and do not count against the circuit")
    void nonTransientErrorsPassThrough() {
        for (int i = 0; i < 6; i++) {
            script.add(() -> {
                throw new IllegalStateException("no recording for unit/DesignAgent");
            });
            assertThatThrownBy(() -> client.complete(REQUEST))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no recording");
        }

        assertThat(calls).hasValue(6);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
}

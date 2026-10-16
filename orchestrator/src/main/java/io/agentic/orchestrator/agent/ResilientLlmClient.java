package io.agentic.orchestrator.agent;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.function.Supplier;

/**
 * ORCH-023: wraps any {@link LlmClient} in a Resilience4j retry around a circuit breaker. Every
 * attempt counts towards the breaker; once it opens, calls fail fast with
 * {@link LlmUnavailableException} and are not retried, so a model outage costs no waiting.
 * Which errors are transient is the delegate's call: it throws {@link LlmUnavailableException}.
 */
public final class ResilientLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public ResilientLlmClient(LlmClient delegate, CircuitBreaker breaker, Retry retry) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.retry = retry;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        Supplier<LlmResponse> guarded = CircuitBreaker.decorateSupplier(breaker, () -> delegate.complete(request));
        try {
            return Retry.decorateSupplier(retry, guarded).get();
        } catch (CallNotPermittedException e) {
            throw new LlmUnavailableException(
                    "circuit breaker '" + breaker.getName() + "' is open; not calling the model for agent "
                            + request.agent(),
                    e);
        }
    }
}

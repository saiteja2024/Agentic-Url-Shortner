package io.agentic.orchestrator.agent;

/**
 * A transient model failure (I/O error, HTTP 429 or 5xx, or an open circuit). Only this type is
 * retried and counted by the {@code llm} circuit breaker (ORCH-023).
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

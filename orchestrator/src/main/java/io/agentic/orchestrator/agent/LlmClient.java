package io.agentic.orchestrator.agent;

/** The only way agents reach a model (ORCH-020), so mock and real modes are interchangeable. */
@FunctionalInterface
public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}

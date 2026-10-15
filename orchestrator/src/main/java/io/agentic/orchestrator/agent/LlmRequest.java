package io.agentic.orchestrator.agent;

/** One agent call. {@code scenario}, {@code agent} and {@code attempt} key the mock's recordings. */
public record LlmRequest(String scenario, String agent, String nodeId, int attempt, String system, String prompt) {}

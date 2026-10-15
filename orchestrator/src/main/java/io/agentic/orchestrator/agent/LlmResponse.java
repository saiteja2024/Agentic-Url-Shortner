package io.agentic.orchestrator.agent;

public record LlmResponse(String text, long inputTokens, long outputTokens, String model) {}

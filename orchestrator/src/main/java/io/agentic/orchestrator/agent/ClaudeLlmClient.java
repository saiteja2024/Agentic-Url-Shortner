package io.agentic.orchestrator.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.util.stream.Collectors;

/**
 * Real model calls through the official Anthropic Java SDK (ORCH-020, {@code orchestrator.llm.mode=claude}).
 * Server-side refusal fallbacks are enabled so a safety refusal is retried on a fallback model
 * instead of failing the node outright.
 */
public final class ClaudeLlmClient implements LlmClient {

    private static final long MAX_TOKENS = 16_000L;

    private final AnthropicClient client;
    private final String model;

    public ClaudeLlmClient(String apiKey, String model) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(request.system())
                .addUserMessage(request.prompt())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        Message message = client.messages().create(params);
        if (message.stopReason()
                .map(r -> r.toString().equalsIgnoreCase("refusal"))
                .orElse(false)) {
            throw new IllegalStateException("model refused the request for agent " + request.agent());
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        return new LlmResponse(
                text, message.usage().inputTokens(), message.usage().outputTokens(), model);
    }
}

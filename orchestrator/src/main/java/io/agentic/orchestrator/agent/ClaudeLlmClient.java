package io.agentic.orchestrator.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.util.stream.Collectors;

/**
 * Real model calls through the official Anthropic Java SDK (ORCH-020, {@code orchestrator.llm.mode=claude}).
 * Server-side refusal fallbacks are enabled so a safety refusal is retried on a fallback model
 * instead of failing the node outright.
 *
 * <p>The SDK's own retries are off: {@link ResilientLlmClient} owns retry and the circuit breaker
 * (ORCH-023), and this class only says which failures are transient.
 */
public final class ClaudeLlmClient implements LlmClient {

    private static final long MAX_TOKENS = 16_000L;

    private final AnthropicClient client;
    private final String model;

    public ClaudeLlmClient(String apiKey, String model) {
        this(apiKey, model, null);
    }

    /** @param baseUrl API base URL, or {@code null} for the default */
    public ClaudeLlmClient(String apiKey, String model, String baseUrl) {
        AnthropicOkHttpClient.Builder builder =
                AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
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
        Message message = send(params, request);
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

    private Message send(MessageCreateParams params, LlmRequest request) {
        try {
            return client.messages().create(params);
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            if (status == 429 || status >= 500) {
                throw unavailable("HTTP " + status, request, e);
            }
            throw e;
        } catch (AnthropicIoException | AnthropicRetryableException e) {
            throw unavailable(e.getClass().getSimpleName(), request, e);
        }
    }

    private static LlmUnavailableException unavailable(String what, LlmRequest request, RuntimeException cause) {
        return new LlmUnavailableException(
                "model call failed (" + what + ") for agent " + request.agent() + ": " + cause.getMessage(), cause);
    }
}

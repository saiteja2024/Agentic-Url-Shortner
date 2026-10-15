package io.agentic.orchestrator.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Replays recorded responses from {@code <base>/<scenario>/<agent>.<attempt>.md}, falling back to
 * {@code <agent>.md} (ORCH-020). Deterministic, offline, and free: demos and CI never call a model.
 */
public final class MockLlmClient implements LlmClient {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    private final String base;

    public MockLlmClient(String classpathBase) {
        this.base = classpathBase;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!SAFE_SEGMENT.matcher(request.scenario()).matches()
                || !SAFE_SEGMENT.matcher(request.agent()).matches()) {
            throw new IllegalArgumentException("invalid recording key " + request.scenario() + "/" + request.agent());
        }
        String key = request.scenario() + "/" + request.agent();
        String text = read(base + "/" + key + "." + request.attempt() + ".md");
        if (text == null) {
            text = read(base + "/" + key + ".md");
        }
        if (text == null) {
            throw new IllegalStateException("no recorded response for " + key + " (attempt " + request.attempt() + ")");
        }
        return new LlmResponse(text, estimateTokens(request.system() + request.prompt()), estimateTokens(text), "mock");
    }

    /** About four characters per token: good enough to exercise budgets without a tokenizer. */
    private static long estimateTokens(String text) {
        return Math.max(1, (text.length() + 3) / 4);
    }

    private static String read(String resource) {
        try (InputStream in = MockLlmClient.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

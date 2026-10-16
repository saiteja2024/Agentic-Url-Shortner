package io.agentic.orchestrator.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs the real SDK against a local stub of the Messages API, so error mapping is tested end to end. */
class ClaudeLlmClientTest {

    private static final LlmRequest REQUEST = new LlmRequest("unit", "DesignAgent", "design", 1, "system", "prompt");
    private static final String MESSAGE =
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":3,"output_tokens":2}}""";
    private static final String ERROR =
            """
            {"type":"error","error":{"type":"api_error","message":"stubbed"}}""";

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ClaudeLlmClient clientAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new ClaudeLlmClient(
                "test-key",
                "claude-opus-5-5",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    @Tag("ORCH-020")
    @DisplayName("ORCH-020: returns the model's text and token usage")
    void completes() throws IOException {
        LlmResponse response = clientAnswering(200, MESSAGE).complete(REQUEST);

        assertThat(response.text()).isEqualTo("hello");
        assertThat(response.inputTokens()).isEqualTo(3);
        assertThat(response.outputTokens()).isEqualTo(2);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {429, 500, 529})
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: rate limits and server errors are transient, and the SDK does not retry on its own")
    void transientStatuses(int status) throws IOException {
        ClaudeLlmClient client = clientAnswering(status, ERROR);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("DesignAgent");
        assertThat(requests).hasValue(1);
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: a bad request is not transient")
    void badRequestIsNotTransient() throws IOException {
        ClaudeLlmClient client = clientAnswering(400, ERROR);

        assertThatThrownBy(() -> client.complete(REQUEST)).isNotInstanceOf(LlmUnavailableException.class);
    }

    @Test
    @Tag("ORCH-023")
    @DisplayName("ORCH-023: a connection failure is transient")
    void connectionFailureIsTransient() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ClaudeLlmClient client = new ClaudeLlmClient("test-key", "claude-opus-5-5", "http://127.0.0.1:" + port);

        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LlmUnavailableException.class);
    }
}

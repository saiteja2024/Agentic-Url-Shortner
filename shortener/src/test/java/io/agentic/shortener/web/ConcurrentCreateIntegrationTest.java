package io.agentic.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.agentic.shortener.support.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Real HTTP server + real Postgres: races are decided by the database, not by test timing. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ConcurrentCreateIntegrationTest {

    private static final int REQUESTS = 50;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Tag("SHORT-005")
    @DisplayName("SHORT-005: 50 concurrent creates all succeed with distinct codes")
    void concurrentCreatesAreDistinct() throws Exception {
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            String alias = i % 5 == 0
                    ? ",\"alias\":\"cc-" + UUID.randomUUID().toString().substring(0, 12) + "\""
                    : "";
            calls.add(() -> postUrl("{\"url\":\"https://www.example.org/" + UUID.randomUUID() + "\"" + alias + "}"));
        }

        List<HttpResponse<String>> responses = runTogether(calls);

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        List<String> codes = responses.stream()
                .map(r -> (String) JsonPath.read(r.body(), "$.code"))
                .toList();
        assertThat(codes).doesNotHaveDuplicates().hasSize(REQUESTS);
    }

    @Test
    @Tag("SHORT-005")
    @DisplayName("SHORT-005: racing on one alias yields exactly one 201 and the rest 409")
    void aliasRaceHasSingleWinner() throws Exception {
        String alias = "race-" + UUID.randomUUID().toString().substring(0, 8);
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            calls.add(() -> postUrl(
                    "{\"url\":\"https://www.example.org/" + UUID.randomUUID() + "\",\"alias\":\"" + alias + "\"}"));
        }

        List<Integer> statuses =
                runTogether(calls).stream().map(HttpResponse::statusCode).toList();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    }

    private HttpResponse<String> postUrl(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Releases every call at once from a start gate, then waits for all of them. Unfinished calls are
     * cancelled and interrupted, so a stalled request fails the test instead of hanging the suite.
     */
    private static <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return call.call();
                }));
            }
            gate.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            futures.forEach(f -> f.cancel(true));
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("request threads did not stop after interrupt");
            }
        }
    }
}

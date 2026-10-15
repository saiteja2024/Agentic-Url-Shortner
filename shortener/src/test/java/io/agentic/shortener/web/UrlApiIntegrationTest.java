package io.agentic.shortener.web;

import static io.agentic.shortener.contract.OpenApiContract.conformsToSpec;
import static io.agentic.shortener.contract.OpenApiContract.responseConformsToSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.agentic.shortener.support.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UrlApiIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    // ---- helpers ---------------------------------------------------------------------

    /** A long URL nobody else in the suite uses, so persistence assertions are isolated. */
    private static String uniqueLongUrl() {
        return "https://www.example.org/" + UUID.randomUUID();
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(
                post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String createCode(String longUrl) throws Exception {
        String body = create("{\"url\":\"" + longUrl + "\"}")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.code");
    }

    private long rowsFor(String longUrl) {
        return jdbc.sql("SELECT count(*) FROM shortener.urls WHERE long_url = ?")
                .param(longUrl)
                .query(Long.class)
                .single();
    }

    private long clicksFor(String code) {
        return jdbc.sql(
                        "SELECT count(*) FROM shortener.clicks c JOIN shortener.urls u ON u.id = c.url_id WHERE u.code = ?")
                .param(code)
                .query(Long.class)
                .single();
    }

    private void insertExpired(String code, String longUrl) {
        Instant created = Instant.now().minus(2, ChronoUnit.DAYS);
        jdbc.sql("INSERT INTO shortener.urls (code, long_url, created_at, expires_at) VALUES (?, ?, ?, ?)")
                .params(
                        code,
                        longUrl,
                        java.sql.Timestamp.from(created),
                        java.sql.Timestamp.from(created.plus(1, ChronoUnit.DAYS)))
                .update();
    }

    // ---- create ----------------------------------------------------------------------

    @Nested
    class Create {

        @Test
        @Tag("SHORT-001")
        @DisplayName("SHORT-001: creates a link with a 7-char Base62 code, Location and body")
        void createsWithGeneratedCode() throws Exception {
            String longUrl = uniqueLongUrl();

            create("{\"url\":\"" + longUrl + "\"}")
                    .andExpect(status().isCreated())
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")))
                    .andExpect(jsonPath("$.longUrl").value(longUrl))
                    .andExpect(jsonPath("$.shortUrl").value(matchesPattern("http://localhost:\\d+/[0-9A-Za-z]{7}")))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.expiresAt").doesNotExist())
                    .andExpect(header().string("Location", matchesPattern("/api/v1/urls/[0-9A-Za-z]{7}/stats")));

            assertThat(rowsFor(longUrl)).isEqualTo(1);
        }

        @Test
        @Tag("SHORT-003")
        @DisplayName("SHORT-003: a free alias becomes the code; a taken alias is 409 and unchanged")
        void aliasCreateAndConflict() throws Exception {
            String alias = "it-" + UUID.randomUUID().toString().substring(0, 8);
            String first = uniqueLongUrl();

            create("{\"url\":\"" + first + "\",\"alias\":\"" + alias + "\"}")
                    .andExpect(status().isCreated())
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.code").value(alias));

            create("{\"url\":\"" + uniqueLongUrl() + "\",\"alias\":\"" + alias + "\"}")
                    .andExpect(status().isConflict())
                    .andExpect(conformsToSpec())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(409));

            mockMvc.perform(get("/" + alias)).andExpect(header().string("Location", first));
        }

        @ParameterizedTest
        @Tag("SHORT-003")
        @DisplayName("SHORT-003: malformed and reserved aliases are 400 naming the alias field")
        @ValueSource(strings = {"ab", "has space", "bad!char", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "api", "Actuator"})
        void badAliases(String alias) throws Exception {
            String longUrl = uniqueLongUrl();
            create("{\"url\":\"" + longUrl + "\",\"alias\":\"" + alias + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(responseConformsToSpec())
                    .andExpect(jsonPath("$.errors[*].field").value(hasItem("alias")));
            assertThat(rowsFor(longUrl)).isZero();
        }

        @Test
        @Tag("SHORT-004")
        @DisplayName("SHORT-004: a future expiry is stored and echoed")
        void futureExpiry() throws Exception {
            create("{\"url\":\"" + uniqueLongUrl() + "\",\"expiresAt\":\"2099-12-31T23:59:59Z\"}")
                    .andExpect(status().isCreated())
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.expiresAt").value("2099-12-31T23:59:59Z"));
        }

        @Test
        @Tag("SHORT-004")
        @DisplayName("SHORT-004: a past expiry is 400 naming expiresAt")
        void pastExpiry() throws Exception {
            String longUrl = uniqueLongUrl();
            create("{\"url\":\"" + longUrl + "\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.errors[0].field").value("expiresAt"));
            assertThat(rowsFor(longUrl)).isZero();
        }
    }

    // ---- validation ------------------------------------------------------------------

    @Nested
    class Validation {

        @ParameterizedTest
        @Tag("SHORT-011")
        @Tag("SHORT-012")
        @Tag("SHORT-013")
        @Tag("SHORT-014")
        @DisplayName("SHORT-011, SHORT-012, SHORT-013, SHORT-014: unsafe targets are 400 on url and not persisted")
        @ValueSource(
                strings = {
                    "javascript:alert(1)", // 011
                    "https://user:pw@www.example.org/", // 011
                    "http://localhost:8080/", // 012
                    "http://2130706433/", // 012 decimal loopback
                    "http://192.168.1.1/", // 013
                    "http://[fd00::1]/", // 013
                    "http://169.254.169.254/latest/meta-data/", // 014
                    "http://metadata.google.internal/" // 014
                })
        void rejectsUnsafeTargets(String url) throws Exception {
            create("{\"url\":\"" + url + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(conformsToSpec())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[0].field").value("url"));
            assertThat(rowsFor(url)).isZero();
        }

        @ParameterizedTest
        @Tag("SHORT-015")
        @DisplayName("SHORT-015: missing, blank, oversized url and malformed JSON are 400 with errors[]")
        @ValueSource(
                strings = {
                    "{}",
                    "{\"url\":\"\"}",
                    "{\"url\":\"   \"}",
                    "{\"url\":null}",
                    "{\"url\":\"https://www.example.org/LONG\"}",
                    "{\"url\":",
                    "not json",
                    "{\"url\":\"https://www.example.org/\",\"unknown\":1}",
                    "{\"url\":\"https://www.example.org/\",\"expiresAt\":\"tomorrow\"}"
                })
        void rejectsBadShapes(String body) throws Exception {
            String json = body.replace("LONG", "a".repeat(2100));
            create(json)
                    .andExpect(status().isBadRequest())
                    .andExpect(responseConformsToSpec())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors").isNotEmpty());
        }
    }

    // ---- redirect + clicks -----------------------------------------------------------

    @Nested
    class Redirect {

        @Test
        @Tag("SHORT-006")
        @DisplayName("SHORT-006: redirects with 302, Location and Cache-Control: no-store")
        void redirects() throws Exception {
            String longUrl = uniqueLongUrl();
            String code = createCode(longUrl);

            mockMvc.perform(get("/" + code))
                    .andExpect(status().isFound())
                    .andExpect(conformsToSpec())
                    .andExpect(header().string("Location", longUrl))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        @Test
        @Tag("SHORT-007")
        @DisplayName("SHORT-007: every redirect records one click with its referrer")
        void recordsClicks() throws Exception {
            String code = createCode(uniqueLongUrl());

            mockMvc.perform(get("/" + code).header("Referer", "https://news.example.com/a"));
            mockMvc.perform(get("/" + code));

            assertThat(clicksFor(code)).isEqualTo(2);
            assertThat(jdbc.sql("SELECT referrer FROM shortener.clicks c JOIN shortener.urls u ON u.id = c.url_id"
                                    + " WHERE u.code = ? ORDER BY c.id")
                            .param(code)
                            .query(String.class)
                            .list())
                    .containsExactly("https://news.example.com/a", null);
        }

        @Test
        @Tag("SHORT-008")
        @DisplayName("SHORT-008: unknown codes are 404 ProblemDetail on redirect and stats")
        void unknownCode() throws Exception {
            mockMvc.perform(get("/zzNope99"))
                    .andExpect(status().isNotFound())
                    .andExpect(conformsToSpec())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
            mockMvc.perform(get("/api/v1/urls/zzNope99/stats"))
                    .andExpect(status().isNotFound())
                    .andExpect(conformsToSpec());
        }

        @Test
        @Tag("SHORT-009")
        @DisplayName("SHORT-009: expired links are 410 Gone and record no click")
        void expiredLink() throws Exception {
            String code = "exp-" + UUID.randomUUID().toString().substring(0, 8);
            insertExpired(code, uniqueLongUrl());

            mockMvc.perform(get("/" + code))
                    .andExpect(status().isGone())
                    .andExpect(conformsToSpec())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
            assertThat(clicksFor(code)).isZero();
        }

        @Test
        @Tag("SHORT-006")
        @DisplayName("SHORT-006: malformed codes are 400, not a database lookup")
        void malformedCode() throws Exception {
            mockMvc.perform(get("/a!"))
                    .andExpect(status().isBadRequest())
                    .andExpect(responseConformsToSpec())
                    .andExpect(jsonPath("$.errors[0].field").value("code"));
        }
    }

    // ---- stats -----------------------------------------------------------------------

    @Nested
    class Stats {

        @Test
        @Tag("SHORT-010")
        @DisplayName("SHORT-010: totals, last click and top referrers by count with (direct)")
        void statsAggregate() throws Exception {
            String longUrl = uniqueLongUrl();
            String code = createCode(longUrl);
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(get("/" + code).header("Referer", "https://a.example/"));
            }
            mockMvc.perform(get("/" + code).header("Referer", "https://b.example/"));
            mockMvc.perform(get("/" + code));
            mockMvc.perform(get("/" + code));

            mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                    .andExpect(status().isOk())
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.longUrl").value(longUrl))
                    .andExpect(jsonPath("$.expired").value(false))
                    .andExpect(jsonPath("$.totalClicks").value(6))
                    .andExpect(jsonPath("$.lastClickedAt").exists())
                    .andExpect(jsonPath("$.topReferrers[0].referrer").value("https://a.example/"))
                    .andExpect(jsonPath("$.topReferrers[0].clicks").value(3))
                    .andExpect(jsonPath("$.topReferrers[1].referrer").value("(direct)"))
                    .andExpect(jsonPath("$.topReferrers[1].clicks").value(2))
                    .andExpect(jsonPath("$.topReferrers[2].referrer").value("https://b.example/"));
        }

        @Test
        @Tag("SHORT-010")
        @DisplayName("SHORT-010: caps top referrers at 10 and reports a never-clicked link")
        void statsLimitsAndEmpty() throws Exception {
            String code = createCode(uniqueLongUrl());
            mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.totalClicks").value(0))
                    .andExpect(jsonPath("$.lastClickedAt").doesNotExist())
                    .andExpect(jsonPath("$.topReferrers").isEmpty());

            for (int i = 0; i < 12; i++) {
                mockMvc.perform(get("/" + code).header("Referer", "https://r" + i + ".example/"));
            }
            mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.totalClicks").value(12))
                    .andExpect(jsonPath("$.topReferrers.length()").value(10));
        }

        @Test
        @Tag("SHORT-010")
        @DisplayName("SHORT-010: stats stay readable after expiry (assumption A4)")
        void statsForExpired() throws Exception {
            String code = "exs-" + UUID.randomUUID().toString().substring(0, 8);
            insertExpired(code, uniqueLongUrl());

            mockMvc.perform(get("/api/v1/urls/" + code + "/stats"))
                    .andExpect(status().isOk())
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.expired").value(true))
                    .andExpect(jsonPath("$.expiresAt").exists());
        }
    }

    // ---- cross-cutting ---------------------------------------------------------------

    @Nested
    class CrossCutting {

        @Test
        @Tag("SHORT-016")
        @DisplayName("SHORT-016: every error is application/problem+json with type, title, status")
        void uniformProblemDetails() throws Exception {
            for (ResultActions r : new ResultActions[] {
                create("{\"url\":\"ftp://x.example/\"}"),
                mockMvc.perform(get("/api/v1/urls/zzNope99/stats")),
                mockMvc.perform(
                        post("/api/v1/urls").contentType(MediaType.TEXT_PLAIN).content("x"))
            }) {
                r.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").exists())
                        .andExpect(jsonPath("$.title").exists())
                        .andExpect(jsonPath("$.status").isNumber())
                        .andExpect(content().string(not(containsString("Exception"))));
            }
        }

        @Test
        @Tag("SHORT-017")
        @DisplayName("SHORT-017: success and error responses of every operation match the contract")
        void everyOperationConforms() throws Exception {
            String code = createCode(uniqueLongUrl());
            mockMvc.perform(get("/" + code)).andExpect(conformsToSpec());
            mockMvc.perform(get("/api/v1/urls/" + code + "/stats")).andExpect(conformsToSpec());
            create("{\"url\":\"http://10.0.0.1/\"}").andExpect(conformsToSpec());
            mockMvc.perform(get("/zzNope99")).andExpect(conformsToSpec());
            mockMvc.perform(get("/api/v1/urls/zzNope99/stats"))
                    .andExpect(conformsToSpec())
                    .andExpect(jsonPath("$.instance").value(endsWith("/api/v1/urls/zzNope99/stats")));
        }
    }
}

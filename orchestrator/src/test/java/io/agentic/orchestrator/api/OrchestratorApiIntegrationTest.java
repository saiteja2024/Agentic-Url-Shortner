package io.agentic.orchestrator.api;

import static io.agentic.orchestrator.contract.OpenApiContract.conformsToSpec;
import static io.agentic.orchestrator.contract.OpenApiContract.responseConformsToSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.agentic.orchestrator.support.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** End-to-end over HTTP: real Postgres, real git workspaces, real Maven gates, mock LLM. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrchestratorApiIntegrationTest {

    private static final String KEY = "test-api-key";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path workspaces = Files.createTempDirectory("workspaces");
        registry.add("orchestrator.api-key", () -> KEY);
        registry.add("orchestrator.workspaces-dir", workspaces::toString);
        registry.add("orchestrator.target-project", () -> Path.of("../scenarios/seed/linkkit")
                .toAbsolutePath()
                .normalize()
                .toString());
    }

    @Autowired
    private MockMvc mvc;

    // ---- helpers ------------------------------------------------------------------------------------

    private ResultActions postJson(String path, String body, String key) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("X-API-Key", key);
        }
        return mvc.perform(request);
    }

    private String start(String scenario, String mode, String request) throws Exception {
        String body = postJson(
                        "/api/v1/runs",
                        """
                        {"scenario":"%s","mode":"%s","request":"%s"}"""
                                .formatted(scenario, mode, request),
                        KEY)
                .andExpect(status().isAccepted())
                .andExpect(conformsToSpec())
                .andExpect(header().string("Location", containsString("/api/v1/runs/")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private Map<String, Object> run(String id) throws Exception {
        return JsonPath.read(
                mvc.perform(get("/api/v1/runs/" + id)).andReturn().getResponse().getContentAsString(), "$");
    }

    private String nodeStatus(String id, String node) throws Exception {
        List<String> statuses = JsonPath.read(
                mvc.perform(get("/api/v1/runs/" + id)).andReturn().getResponse().getContentAsString(),
                "$.nodes[?(@.id == '" + node + "')].status");
        return statuses.getFirst();
    }

    private void awaitNode(String id, String node, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (!expected.equals(nodeStatus(id, node))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(node + " never reached " + expected + "; run: " + run(id));
            }
            Thread.sleep(200);
        }
    }

    private void awaitRun(String id, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (!expected.equals(run(id).get("status"))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("run never reached " + expected + ": " + run(id));
            }
            Thread.sleep(200);
        }
    }

    private void approve(String id, String node) throws Exception {
        postJson("/api/v1/runs/" + id + "/nodes/" + node + "/approve", "{\"by\":\"sai\"}", KEY)
                .andExpect(status().isOk())
                .andExpect(conformsToSpec());
    }

    private Map<String, Object> report(String id) throws Exception {
        String body = mvc.perform(get("/api/v1/runs/" + id + "/report"))
                .andExpect(status().isOk())
                .andExpect(conformsToSpec())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$");
    }

    private List<String> eventTypes(String id) throws Exception {
        String body = mvc.perform(get("/api/v1/runs/" + id + "/events"))
                .andExpect(status().isOk())
                .andExpect(conformsToSpec())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Integer> seqs = JsonPath.read(body, "$[*].seq");
        assertThat(seqs).isSorted();
        return JsonPath.read(body, "$[*].type");
    }

    // ---- security ------------------------------------------------------------------------------------

    @Test
    @Tag("ORCH-037")
    @DisplayName("ORCH-037: mutations without a valid X-API-Key are 401 and change nothing")
    void apiKeyRequired() throws Exception {
        String body = "{\"scenario\":\"greenfield\",\"mode\":\"GREENFIELD\",\"request\":\"x\"}";
        int before = ((List<?>) JsonPath.read(
                        mvc.perform(get("/api/v1/runs"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$"))
                .size();

        postJson("/api/v1/runs", body, null)
                .andExpect(status().isUnauthorized())
                .andExpect(responseConformsToSpec())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        postJson("/api/v1/runs", body, "wrong-key").andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(conformsToSpec())
                .andExpect(jsonPath("$.length()").value(before));
    }

    @Test
    @Tag("ORCH-037")
    @DisplayName("ORCH-037: unknown runs are 404, invalid requests 400, decisions on non-waiting nodes 409")
    void errorMapping() throws Exception {
        mvc.perform(get("/api/v1/runs/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(conformsToSpec());
        postJson("/api/v1/runs", "{\"scenario\":\"Bad Scenario\",\"mode\":\"GREENFIELD\",\"request\":\"x\"}", KEY)
                .andExpect(status().isBadRequest())
                .andExpect(responseConformsToSpec());

        String id = start("greenfield", "GREENFIELD", "Add click analytics");
        awaitNode(id, "design", "WAITING_APPROVAL");
        postJson("/api/v1/runs/" + id + "/nodes/requirements/approve", "{\"by\":\"sai\"}", KEY)
                .andExpect(status().isConflict())
                .andExpect(conformsToSpec());
        postJson("/api/v1/runs/" + id + "/nodes/ghost/approve", "{\"by\":\"sai\"}", KEY)
                .andExpect(status().isNotFound());

        // ORCH-035 via the API: the kill switch, then a second kill is a conflict.
        postJson("/api/v1/runs/" + id + "/kill", "{\"by\":\"sai\",\"reason\":\"demo stop\"}", KEY)
                .andExpect(status().isOk())
                .andExpect(conformsToSpec());
        awaitRun(id, "CANCELLED");
        assertThat(run(id).get("stopReason")).isEqualTo("demo stop");
        postJson("/api/v1/runs/" + id + "/kill", "{\"by\":\"sai\",\"reason\":\"again\"}", KEY)
                .andExpect(status().isConflict());
    }

    // ---- scenarios -----------------------------------------------------------------------------------

    @Test
    @Tag("ORCH-021")
    @Tag("ORCH-022")
    @Tag("ORCH-042")
    @Tag("ORCH-043")
    @DisplayName("ORCH-021, ORCH-022, ORCH-042, ORCH-043: greenfield — parallel stages, approvals, merge, full report")
    void greenfield() throws Exception {
        String id = start("greenfield", "GREENFIELD", "Add click analytics with top-N referrers");

        awaitNode(id, "design", "WAITING_APPROVAL");
        mvc.perform(get("/api/v1/runs/" + id + "/approvals"))
                .andExpect(conformsToSpec())
                .andExpect(jsonPath("$[0].nodeId").value("design"))
                .andExpect(jsonPath("$[0].kind").value("APPROVAL"));
        approve(id, "design");

        awaitNode(id, "release", "WAITING_APPROVAL");
        mvc.perform(get("/api/v1/runs/" + id + "/approvals"))
                .andExpect(jsonPath("$[0].reasons[0]").value("policy 'merge' matched action:merge"));
        approve(id, "release");
        awaitRun(id, "SUCCEEDED");

        Map<String, Object> report = report(id);
        assertThat(report.get("status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<List<String>>read(report, "$.gates[?(@.outcome == 'PASS')].gate"))
                .contains("requirements-ids", "compile", "tests-pass", "coverage", "allowed-paths", "secret-scan");
        assertThat(JsonPath.<List<String>>read(report, "$.approvals[*].nodeId")).containsExactly("design", "release");
        assertThat(JsonPath.<List<String>>read(report, "$.artifacts[*].contentHash"))
                .allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(JsonPath.<Integer>read(report, "$.tokens.input")).isPositive();
        assertThat(JsonPath.<List<String>>read(report, "$.nodes[?(@.id == 'impact-analysis')].status"))
                .containsExactly("DONE");

        List<String> types = eventTypes(id);
        assertThat(types.getFirst()).isEqualTo("RUN_STARTED");
        assertThat(types).contains("MERGED", "LLM_USAGE", "NODE_SKIPPED");
        assertThat(types.getLast()).isEqualTo("RUN_FINISHED");
    }

    @Test
    @Tag("ORCH-032")
    @Tag("ORCH-033")
    @DisplayName(
            "ORCH-032, ORCH-033: brownfield — impact analysis, regression retried, tests escalated and rolled back")
    void brownfield() throws Exception {
        String id = start("brownfield", "BROWNFIELD", "Links must support an optional expiry");

        awaitNode(id, "design", "WAITING_APPROVAL");
        approve(id, "design");
        awaitNode(id, "test", "WAITING_APPROVAL");
        mvc.perform(get("/api/v1/runs/" + id + "/approvals"))
                .andExpect(jsonPath("$[0].kind").value("ESCALATION"))
                .andExpect(jsonPath("$[0].reasons[0]", containsString("retries exhausted after 3")));
        postJson(
                        "/api/v1/runs/" + id + "/nodes/test/reject",
                        "{\"by\":\"sai\",\"reason\":\"test misreads REQ-002\"}",
                        KEY)
                .andExpect(status().isOk());
        awaitRun(id, "FAILED");

        Map<String, Object> report = report(id);
        assertThat(JsonPath.<List<Integer>>read(report, "$.nodes[?(@.id == 'implement')].attempts"))
                .containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(report, "$.nodes[?(@.id == 'test')].attempts"))
                .containsExactly(3);
        assertThat(JsonPath.<Integer>read(report, "$.retries")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(report, "$.rollbacks[*].nodeId")).containsExactly("test");
        assertThat(JsonPath.<List<String>>read(report, "$.gates[?(@.outcome == 'FAIL')].gate"))
                .contains("tests-pass");
        assertThat(JsonPath.<List<String>>read(report, "$.nodes[?(@.id == 'impact-analysis')].status"))
                .containsExactly("DONE");
        assertThat(eventTypes(id)).contains("NODE_RECOVERED", "ROLLBACK");
    }

    @Test
    @Tag("ORCH-034")
    @Tag("ORCH-036")
    @DisplayName(
            "ORCH-034, ORCH-036: ambiguous — clarifying questions, answers and assumptions, then a minimal re-plan")
    void ambiguous() throws Exception {
        String id = start("ambiguous", "GREENFIELD", "Make the short links better for our marketing team");

        awaitNode(id, "requirements", "WAITING_APPROVAL");
        mvc.perform(get("/api/v1/runs/" + id + "/approvals"))
                .andExpect(conformsToSpec())
                .andExpect(jsonPath("$[0].kind").value("CLARIFICATION"))
                .andExpect(jsonPath("$[0].questions.length()").value(3));
        postJson(
                        "/api/v1/runs/" + id + "/nodes/requirements/answers",
                        "{\"by\":\"sai\",\"answers\":{\"Q1\":\"Yes: 3-32 chars, a-z 0-9 and hyphen\"}}",
                        KEY)
                .andExpect(status().isOk())
                .andExpect(conformsToSpec());

        awaitNode(id, "design", "WAITING_APPROVAL");
        approve(id, "design");
        awaitNode(id, "release", "WAITING_APPROVAL");
        approve(id, "release");
        awaitRun(id, "SUCCEEDED");

        Map<String, Object> first = report(id);
        assertThat(JsonPath.<List<String>>read(first, "$.clarifications[?(@.answer)].questionId"))
                .containsExactly("Q1");
        assertThat(JsonPath.<List<String>>read(first, "$.clarifications[?(@.assumption)].questionId"))
                .containsExactly("Q2", "Q3");

        postJson(
                        "/api/v1/runs/" + id + "/nodes/requirements/replan",
                        "{\"by\":\"sai\",\"input\":\"Also block the reserved words admin and api\"}",
                        KEY)
                .andExpect(status().isAccepted())
                .andExpect(conformsToSpec());
        awaitNode(id, "design", "WAITING_APPROVAL");
        approve(id, "design");
        awaitRun(id, "SUCCEEDED");

        Map<String, Object> second = report(id);
        assertThat(JsonPath.<List<Integer>>read(second, "$.nodes[?(@.id == 'requirements')].attempts"))
                .containsExactly(3);
        assertThat(JsonPath.<List<Integer>>read(second, "$.nodes[?(@.id == 'design')].attempts"))
                .containsExactly(2);
        assertThat(JsonPath.<List<Integer>>read(second, "$.nodes[?(@.id == 'implement')].attempts"))
                .containsExactly(1);
        assertThat(eventTypes(id)).contains("REPLAN_STARTED", "REPLAN_COMPARED");
    }
}

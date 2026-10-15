package io.agentic.orchestrator.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The observability config files in the repo are checked like code. */
class ObservabilityConfigTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final YAMLMapper YAML = new YAMLMapper();

    private static JsonNode yaml(String relative) throws Exception {
        return YAML.readTree(Files.readString(ROOT.resolve(relative)));
    }

    @Test
    @Tag("OBS-001")
    @DisplayName("OBS-001: Prometheus scrapes /actuator/prometheus on both services")
    void prometheusScrapesBothServices() throws Exception {
        JsonNode config = yaml("observability/prometheus/prometheus.yml");
        String jobs = config.path("scrape_configs").toString();

        assertThat(jobs).contains("host.docker.internal:8080", "host.docker.internal:8081", "/actuator/prometheus");
    }

    @Test
    @Tag("OBS-004")
    @DisplayName("OBS-004: Grafana provisions the Prometheus datasource and the dashboards folder")
    void grafanaProvisioning() throws Exception {
        JsonNode datasource = yaml("observability/grafana/provisioning/datasources/prometheus.yml")
                .path("datasources")
                .get(0);
        assertThat(datasource.path("uid").asText()).isEqualTo("prometheus");
        assertThat(datasource.path("url").asText()).isEqualTo("http://prometheus:9090");

        JsonNode provider = yaml("observability/grafana/provisioning/dashboards/dashboards.yml")
                .path("providers")
                .get(0);
        assertThat(provider.path("options").path("path").asText()).isEqualTo("/var/lib/grafana/dashboards");

        String compose = Files.readString(ROOT.resolve("docker-compose.yml"));
        assertThat(compose).contains("./observability/grafana/dashboards:/var/lib/grafana/dashboards");
        assertThat(Files.isDirectory(ROOT.resolve("observability/grafana/dashboards")))
                .isTrue();
    }

    /** Metric families the services expose (prefixes; histogram/summary suffixes allowed). */
    private static final List<String> KNOWN_METRICS = List.of(
            "orchestrator_runs_completed_total",
            "orchestrator_runs_active",
            "orchestrator_run_seconds",
            "orchestrator_node_seconds",
            "orchestrator_gate_seconds",
            "orchestrator_approval_seconds",
            "orchestrator_retries_total",
            "orchestrator_rollbacks_total",
            "orchestrator_gate_failures_total",
            "orchestrator_recovery_seconds",
            "orchestrator_llm_tokens_total",
            "http_server_requests_seconds",
            "shortener_urls_issued_total",
            "shortener_redirects_total",
            "shortener_click_record_failures_total",
            "shortener_code_collisions_total",
            "jvm_memory_used_bytes",
            "hikaricp_connections_active",
            "up");

    private static final Pattern METRIC =
            Pattern.compile("\\b([a-z_]+(?:_total|_seconds|_bytes|_active|_bucket|_sum|_count|_max)|up)\\b");

    @Test
    @Tag("OBS-005")
    @DisplayName("OBS-005: the three dashboards exist, use the Prometheus datasource and only known metrics")
    void dashboards() throws Exception {
        List<String> titles = new ArrayList<>();
        for (String file : List.of("orchestrator-overview.json", "governance.json", "service-health.json")) {
            JsonNode dashboard = new ObjectMapper()
                    .readTree(Files.readString(ROOT.resolve("observability/grafana/dashboards/" + file)));
            titles.add(dashboard.path("title").asText());
            assertThat(dashboard.path("uid").asText()).isNotBlank();
            assertThat(dashboard.path("panels")).isNotEmpty();
            for (JsonNode panel : dashboard.path("panels")) {
                if (panel.path("type").asText().equals("row")) {
                    continue;
                }
                assertThat(panel.path("datasource").path("uid").asText())
                        .as(panel.path("title").asText())
                        .isEqualTo("prometheus");
                for (JsonNode target : panel.path("targets")) {
                    Matcher metrics = METRIC.matcher(target.path("expr").asText());
                    while (metrics.find()) {
                        String metric = metrics.group(1);
                        assertThat(KNOWN_METRICS)
                                .as(
                                        "metric %s in panel %s",
                                        metric, panel.path("title").asText())
                                .anyMatch(metric::startsWith);
                    }
                }
            }
        }
        assertThat(titles).containsExactly("Orchestrator Overview", "Governance", "Service Health");
    }

    @Test
    @Tag("OBS-006")
    @DisplayName("OBS-006: three alert rules with severity and summary are loaded by Prometheus")
    void alertRules() throws Exception {
        JsonNode rules = yaml("observability/prometheus/alerts.yml")
                .path("groups")
                .get(0)
                .path("rules");
        List<String> names = new ArrayList<>();
        for (JsonNode rule : rules) {
            names.add(rule.path("alert").asText());
            assertThat(rule.path("labels").path("severity").asText()).isNotBlank();
            assertThat(rule.path("annotations").path("summary").asText()).isNotBlank();
            assertThat(rule.path("expr").asText()).isNotBlank();
        }
        assertThat(names)
                .containsExactly("OrchestratorRunSuccessRateLow", "OrchestratorRollbackSpike", "ServiceP95LatencyHigh");
        assertThat(Files.readString(ROOT.resolve("observability/prometheus/prometheus.yml")))
                .contains("/etc/prometheus/alerts.yml");
    }
}

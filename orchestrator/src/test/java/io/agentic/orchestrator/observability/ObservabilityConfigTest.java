package io.agentic.orchestrator.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
}

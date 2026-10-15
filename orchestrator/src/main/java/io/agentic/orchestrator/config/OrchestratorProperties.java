package io.agentic.orchestrator.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param pipelineLocation Spring resource holding the pipeline YAML (copied from specs/orchestrator)
 * @param maxParallelNodes size of the bounded pool nodes run on (ORCH-005)
 */
@Validated
@ConfigurationProperties("orchestrator")
public record OrchestratorProperties(@NotBlank String pipelineLocation, @Min(1) @Max(64) int maxParallelNodes) {}

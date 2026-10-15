package io.agentic.orchestrator.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/** specs/orchestrator/pipeline.yaml, as data (ORCH-001). */
public record PipelineDefinition(
        String apiVersion,
        String kind,
        Metadata metadata,
        @Nullable Defaults defaults,
        Map<String, GateSpec> gates,
        List<ApprovalRule> approvalPolicy,
        List<StageSpec> stages) {

    public record Metadata(String name, String version) {}

    public record Defaults(@Nullable Retries retries, @Nullable String timeout, @Nullable String onExhausted) {}

    public record Retries(int maxAttempts, boolean feedbackToPrompt) {}

    public PipelineDefinition {
        gates = gates == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(gates));
        approvalPolicy = approvalPolicy == null ? List.of() : List.copyOf(approvalPolicy);
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /** @throws IllegalArgumentException if no stage has this id */
    public StageSpec stage(String id) {
        return stages.stream()
                .filter(s -> id.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown stage '" + id + "'"));
    }
}

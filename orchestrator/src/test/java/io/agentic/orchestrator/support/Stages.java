package io.agentic.orchestrator.support;

import io.agentic.orchestrator.pipeline.GateSpec;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.StageSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact builders for test pipelines. */
public final class Stages {

    private Stages() {}

    public static StageSpec stage(String id, String... dependsOn) {
        return new StageSpec(id, "agent-" + id, null, null, Arrays.asList(dependsOn), null, null, null, null, null);
    }

    public static StageSpec withGates(StageSpec s, List<String> entry, List<String> exit) {
        return new StageSpec(
                s.id(),
                s.agent(),
                s.description(),
                s.when(),
                s.dependsOn(),
                s.inputs(),
                s.outputs(),
                s.allowedPaths(),
                entry,
                exit);
    }

    public static StageSpec when(StageSpec s, String when) {
        return new StageSpec(
                s.id(),
                s.agent(),
                s.description(),
                when,
                s.dependsOn(),
                s.inputs(),
                s.outputs(),
                s.allowedPaths(),
                s.entryGates(),
                s.exitGates());
    }

    public static PipelineDefinition pipeline(Map<String, GateSpec> gates, StageSpec... stages) {
        return new PipelineDefinition(
                "agentic.io/v1alpha1",
                "Pipeline",
                new PipelineDefinition.Metadata("test", "0.0.1"),
                null,
                new LinkedHashMap<>(gates),
                List.of(),
                List.of(stages));
    }

    public static PipelineDefinition pipeline(StageSpec... stages) {
        return pipeline(Map.of(), stages);
    }

    public static GateSpec gate(String type) {
        return new GateSpec(type, null, null, null, null);
    }
}

package io.agentic.orchestrator.pipeline;

import java.util.List;
import org.springframework.lang.Nullable;

/** One stage of the pipeline; becomes one DAG node per run. */
public record StageSpec(
        String id,
        String agent,
        @Nullable String description,
        @Nullable String when,
        List<String> dependsOn,
        List<String> inputs,
        List<String> outputs,
        List<String> allowedPaths,
        List<String> entryGates,
        List<String> exitGates) {

    public StageSpec {
        dependsOn = copy(dependsOn);
        inputs = copy(inputs);
        outputs = copy(outputs);
        allowedPaths = copy(allowedPaths);
        entryGates = copy(entryGates);
        exitGates = copy(exitGates);
    }

    private static List<String> copy(@Nullable List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

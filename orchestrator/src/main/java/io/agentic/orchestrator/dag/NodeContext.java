package io.agentic.orchestrator.dag;

import io.agentic.orchestrator.pipeline.StageSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything an agent gets: the stage, the run mode and its direct dependencies' artifacts. */
public record NodeContext(
        UUID runId, String nodeId, StageSpec stage, RunMode mode, int attempt, Map<String, List<Artifact>> upstream) {

    public NodeContext {
        upstream = Map.copyOf(upstream);
    }
}

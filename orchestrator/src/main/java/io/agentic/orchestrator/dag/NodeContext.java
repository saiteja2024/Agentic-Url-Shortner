package io.agentic.orchestrator.dag;

import io.agentic.orchestrator.pipeline.StageSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Everything an agent gets: the stage, the run's request (or a re-plan override), its direct
 * dependencies' artifacts, answers to its clarifying questions, the failure from its previous
 * attempt (fed back into the prompt, ORCH-032) and the workspace it may change.
 */
public record NodeContext(
        UUID runId,
        String nodeId,
        StageSpec stage,
        RunMode mode,
        String scenario,
        String input,
        int attempt,
        Map<String, List<Artifact>> upstream,
        Map<String, String> answers,
        @Nullable String feedback,
        @Nullable Path workspace) {

    public NodeContext {
        upstream = Map.copyOf(upstream);
        answers = Map.copyOf(answers);
    }
}

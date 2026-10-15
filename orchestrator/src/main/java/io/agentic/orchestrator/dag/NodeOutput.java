package io.agentic.orchestrator.dag;

import java.util.Map;
import java.util.TreeMap;

/** What a node produced: artifact name → content. */
public record NodeOutput(Map<String, String> artifacts) {

    public NodeOutput {
        artifacts = Map.copyOf(artifacts);
    }

    public static NodeOutput of(Map<String, String> artifacts) {
        return new NodeOutput(artifacts);
    }

    public static NodeOutput empty() {
        return new NodeOutput(Map.of());
    }

    /** Artifacts sorted by name, so recording order is deterministic. */
    public Map<String, String> sorted() {
        return new TreeMap<>(artifacts);
    }
}

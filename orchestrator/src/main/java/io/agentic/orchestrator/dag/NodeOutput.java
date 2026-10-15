package io.agentic.orchestrator.dag;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a node produced: artifacts (name → content), clarifying questions, requested actions
 * (e.g. {@code merge}) and the LLM tokens spent producing it.
 */
public record NodeOutput(
        Map<String, String> artifacts,
        List<Question> questions,
        List<String> actions,
        long inputTokens,
        long outputTokens) {

    public NodeOutput {
        artifacts = Map.copyOf(artifacts);
        questions = List.copyOf(questions);
        actions = List.copyOf(actions);
    }

    public static NodeOutput of(Map<String, String> artifacts) {
        return new NodeOutput(artifacts, List.of(), List.of(), 0, 0);
    }

    public static NodeOutput empty() {
        return of(Map.of());
    }

    /** Artifacts sorted by name, so recording order is deterministic. */
    public Map<String, String> sorted() {
        return new TreeMap<>(artifacts);
    }
}

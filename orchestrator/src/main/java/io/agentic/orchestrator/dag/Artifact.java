package io.agentic.orchestrator.dag;

import java.util.List;
import java.util.UUID;

/** A node output with its SHA-256 and the hashes of its direct parents' outputs (ORCH-011). */
public record Artifact(
        UUID runId, String nodeId, String name, String content, String contentHash, List<String> parentHashes) {

    public Artifact {
        parentHashes = List.copyOf(parentHashes);
    }
}

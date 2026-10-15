package io.agentic.orchestrator.dag;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** One audit event. {@code seq} is strictly increasing within a run (ORCH-012). */
public record RunEvent(
        long seq,
        UUID runId,
        @Nullable String nodeId,
        EventType type,
        @Nullable NodeStatus from,
        @Nullable NodeStatus to,
        Map<String, Object> payload,
        Instant at) {

    public RunEvent {
        payload = Map.copyOf(payload);
    }
}

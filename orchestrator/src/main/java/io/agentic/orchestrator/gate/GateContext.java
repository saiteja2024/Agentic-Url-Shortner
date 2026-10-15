package io.agentic.orchestrator.gate;

import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.dag.RunWorkspace;
import io.agentic.orchestrator.pipeline.GateSpec;
import io.agentic.orchestrator.pipeline.StageSpec;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Input to a gate. {@code output} is null for entry gates (the node hasn't run yet);
 * {@code checkpoint} is the commit the node started from, for gates that inspect its changes.
 */
public record GateContext(
        UUID runId,
        String nodeId,
        String gateId,
        GateSpec spec,
        @Nullable NodeOutput output,
        StageSpec stage,
        RunWorkspace workspace,
        @Nullable String checkpoint) {}

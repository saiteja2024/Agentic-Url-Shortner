package io.agentic.orchestrator.gate;

import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.pipeline.GateSpec;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** Input to a gate. {@code output} is null for entry gates (the node hasn't run yet). */
public record GateContext(UUID runId, String nodeId, String gateId, GateSpec spec, @Nullable NodeOutput output) {}

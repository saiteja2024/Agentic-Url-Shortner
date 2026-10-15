package io.agentic.orchestrator.dag;

import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.PipelineValidator;
import java.time.Clock;
import java.util.concurrent.Executor;

/** Starts runs of a validated pipeline on a bounded executor. */
public class DagEngine {

    private final Executor executor;
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final Clock clock;

    public DagEngine(Executor executor, AgentRegistry agents, GateRegistry gates, Clock clock) {
        this.executor = executor;
        this.agents = agents;
        this.gates = gates;
        this.clock = clock;
    }

    /**
     * Validates the definition, then starts executing it asynchronously.
     *
     * @throws io.agentic.orchestrator.pipeline.PipelineDefinitionException if the definition is invalid
     */
    public Run start(PipelineDefinition definition, RunMode mode, RunListener listener) {
        PipelineValidator.validate(definition);
        Run run = new Run(definition, DagPlan.of(definition.stages()), mode, executor, agents, gates, clock, listener);
        run.start();
        return run;
    }
}

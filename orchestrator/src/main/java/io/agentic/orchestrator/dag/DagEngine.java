package io.agentic.orchestrator.dag;

import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.PipelineValidator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.lang.Nullable;

/** Starts runs of a validated pipeline on a bounded executor. */
public class DagEngine {

    private final Executor executor;
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final @Nullable ScheduledExecutorService timers;

    /** Without metrics, tracing or time budgets: for tests. */
    public DagEngine(Executor executor, AgentRegistry agents, GateRegistry gates, Clock clock) {
        this(executor, agents, gates, clock, new SimpleMeterRegistry(), ObservationRegistry.NOOP, null);
    }

    public DagEngine(
            Executor executor,
            AgentRegistry agents,
            GateRegistry gates,
            Clock clock,
            MeterRegistry meters,
            ObservationRegistry observations,
            @Nullable ScheduledExecutorService timers) {
        this.executor = executor;
        this.agents = agents;
        this.gates = gates;
        this.clock = clock;
        this.meters = meters;
        this.observations = observations;
        this.timers = timers;
    }

    /** A run without a workspace (tests). */
    public Run start(PipelineDefinition definition, RunMode mode, RunListener listener) {
        return start(definition, RunRequest.of(mode), RunWorkspace.NONE, listener);
    }

    /**
     * Validates the definition, then starts executing it asynchronously.
     *
     * @throws io.agentic.orchestrator.pipeline.PipelineDefinitionException if the definition is invalid
     */
    public Run start(PipelineDefinition definition, RunRequest request, RunWorkspace workspace, RunListener listener) {
        PipelineValidator.validate(definition);
        Run run = new Run(
                definition,
                DagPlan.of(definition.stages()),
                request,
                workspace,
                executor,
                agents,
                gates,
                clock,
                listener,
                meters,
                observations);
        run.start();
        if (timers != null && request.timeBudget() != null) {
            timers.schedule(
                    () -> {
                        if (run.status() == RunStatus.RUNNING) {
                            try {
                                run.stop("system", "time budget of " + request.timeBudget() + " exceeded");
                            } catch (IllegalStateException alreadyStopped) {
                                // finished or stopped concurrently
                            }
                        }
                    },
                    request.timeBudget().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        return run;
    }
}

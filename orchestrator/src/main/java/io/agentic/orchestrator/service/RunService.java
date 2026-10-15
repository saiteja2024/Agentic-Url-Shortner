package io.agentic.orchestrator.service;

import io.agentic.orchestrator.config.OrchestratorProperties;
import io.agentic.orchestrator.dag.DagEngine;
import io.agentic.orchestrator.dag.Run;
import io.agentic.orchestrator.dag.RunMode;
import io.agentic.orchestrator.dag.RunRequest;
import io.agentic.orchestrator.dag.RunStatus;
import io.agentic.orchestrator.persistence.RunRecorder;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.workspace.GitWorkspace;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/** Starts runs and keeps the live ones; stops them all on shutdown (ORCH-035). */
@Service
public class RunService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final DagEngine engine;
    private final PipelineDefinition pipeline;
    private final RunRecorder recorder;
    private final OrchestratorProperties properties;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private volatile boolean running;

    public RunService(
            DagEngine engine,
            PipelineDefinition pipeline,
            RunRecorder recorder,
            OrchestratorProperties properties,
            MeterRegistry meters) {
        this.engine = engine;
        this.pipeline = pipeline;
        this.recorder = recorder;
        this.properties = properties;
        Gauge.builder("orchestrator.runs.active", runs, r -> r.values().stream()
                        .filter(run -> run.status() == RunStatus.RUNNING)
                        .count())
                .description("Runs currently executing or waiting for a human")
                .register(meters);
    }

    public Run start(String scenario, RunMode mode, String request, @Nullable Long tokenBudget) {
        if (!running) {
            throw new IllegalStateException("the orchestrator is shutting down; no new runs");
        }
        UUID id = UUID.randomUUID();
        GitWorkspace workspace = GitWorkspace.create(targetProject(), Path.of(properties.workspacesDir()), id);
        RunRequest runRequest = new RunRequest(
                id,
                scenario,
                mode,
                request,
                tokenBudget != null ? tokenBudget : properties.tokenBudget(),
                properties.timeBudget());
        Run run = engine.start(pipeline, runRequest, workspace, recorder);
        runs.put(id, run);
        return run;
    }

    /** The live run, if this instance started it. */
    public Optional<Run> find(UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    public Run require(UUID id) {
        return find(id).orElseThrow(() -> new RunNotFoundException(id));
    }

    public PipelineDefinition pipeline() {
        return pipeline;
    }

    /** Relative paths resolve from the working directory, or its parent when started inside a module. */
    private Path targetProject() {
        Path configured = Path.of(properties.targetProject());
        if (!configured.isAbsolute()
                && !Files.isDirectory(configured)
                && Files.isDirectory(Path.of("..").resolve(configured))) {
            return Path.of("..").resolve(configured).normalize();
        }
        return configured;
    }

    // ---- graceful shutdown (ORCH-035) ---------------------------------------------------------------

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        runs.values().stream().filter(r -> r.status() == RunStatus.RUNNING).forEach(run -> {
            try {
                run.stop("system", "application shutdown");
                run.completion().get(10, TimeUnit.SECONDS);
            } catch (IllegalStateException alreadyFinished) {
                // finished between the filter and the stop
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Run {} did not stop cleanly within 10s", run.id(), e);
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stop runs before the web server and the database pool go away. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}

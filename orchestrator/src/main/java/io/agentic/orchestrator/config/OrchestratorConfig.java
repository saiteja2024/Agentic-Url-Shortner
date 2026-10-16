package io.agentic.orchestrator.config;

import io.agentic.orchestrator.agent.AgentCatalog;
import io.agentic.orchestrator.agent.ClaudeLlmClient;
import io.agentic.orchestrator.agent.LlmClient;
import io.agentic.orchestrator.agent.MockLlmClient;
import io.agentic.orchestrator.agent.ResilientLlmClient;
import io.agentic.orchestrator.dag.AgentRegistry;
import io.agentic.orchestrator.dag.DagEngine;
import io.agentic.orchestrator.gate.BuiltInGates;
import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.PipelineLoader;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrchestratorProperties.class)
class OrchestratorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Loaded and validated at startup: an invalid pipeline fails boot (ORCH-001…003). */
    @Bean
    PipelineDefinition pipelineDefinition(ResourceLoader resources, OrchestratorProperties properties)
            throws IOException {
        try (InputStream in =
                resources.getResource(properties.pipelineLocation()).getInputStream()) {
            return PipelineLoader.load(in);
        }
    }

    /**
     * ORCH-020: mock by default; {@code claude} needs an API key and fails fast without one.
     * ORCH-023: either way, calls go through the {@code llm} retry and circuit breaker.
     */
    @Bean
    LlmClient llmClient(OrchestratorProperties properties, CircuitBreakerRegistry breakers, RetryRegistry retries) {
        return new ResilientLlmClient(model(properties.llm()), breakers.circuitBreaker("llm"), retries.retry("llm"));
    }

    private static LlmClient model(OrchestratorProperties.Llm llm) {
        return switch (llm.mode()) {
            case "mock" -> new MockLlmClient(llm.recordings());
            case "claude" -> {
                if (llm.apiKey() == null || llm.apiKey().isBlank()) {
                    throw new IllegalStateException("orchestrator.llm.mode=claude requires ANTHROPIC_API_KEY");
                }
                yield new ClaudeLlmClient(llm.apiKey(), llm.model());
            }
            default -> throw new IllegalStateException("unknown orchestrator.llm.mode '" + llm.mode() + "'");
        };
    }

    @Bean
    AgentRegistry agentRegistry(LlmClient llm) {
        return new AgentCatalog(llm);
    }

    @Bean
    GateRegistry gateRegistry(OrchestratorProperties properties) {
        return new BuiltInGates(properties.commandTimeout());
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService nodeExecutor(OrchestratorProperties properties) {
        return Executors.newFixedThreadPool(properties.maxParallelNodes(), named("dag-node"));
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService budgetTimers() {
        return Executors.newSingleThreadScheduledExecutor(named("dag-budget"));
    }

    @Bean
    DagEngine dagEngine(
            ExecutorService nodeExecutor,
            AgentRegistry agents,
            GateRegistry gates,
            Clock clock,
            MeterRegistry meters,
            ObservationRegistry observations,
            ScheduledExecutorService budgetTimers) {
        return new DagEngine(nodeExecutor, agents, gates, clock, meters, observations, budgetTimers);
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

package io.agentic.orchestrator.config;

import io.agentic.orchestrator.dag.AgentRegistry;
import io.agentic.orchestrator.dag.DagEngine;
import io.agentic.orchestrator.gate.GateRegistry;
import io.agentic.orchestrator.pipeline.PipelineDefinition;
import io.agentic.orchestrator.pipeline.PipelineLoader;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Bean(destroyMethod = "shutdown")
    ExecutorService nodeExecutor(OrchestratorProperties properties) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(properties.maxParallelNodes(), runnable -> {
            Thread thread = new Thread(runnable, "dag-node-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    DagEngine dagEngine(ExecutorService nodeExecutor, AgentRegistry agents, GateRegistry gates, Clock clock) {
        return new DagEngine(nodeExecutor, agents, gates, clock);
    }
}

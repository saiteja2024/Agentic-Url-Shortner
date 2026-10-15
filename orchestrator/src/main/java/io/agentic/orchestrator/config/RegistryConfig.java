package io.agentic.orchestrator.config;

import io.agentic.orchestrator.dag.AgentRegistry;
import io.agentic.orchestrator.gate.GateRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fallback registries; the agents and governance modules register the real implementations. */
@Configuration(proxyBeanMethods = false)
class RegistryConfig {

    @Bean
    @ConditionalOnMissingBean
    AgentRegistry agentRegistry() {
        return agent -> ctx -> {
            throw new IllegalStateException("no implementation registered for agent '" + agent + "'");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    GateRegistry gateRegistry() {
        return GateRegistry.defaults();
    }
}

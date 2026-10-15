package io.agentic.orchestrator.dag;

/** Maps a stage's {@code agent} name to its implementation. */
@FunctionalInterface
public interface AgentRegistry {

    NodeExecutor forAgent(String agent);
}

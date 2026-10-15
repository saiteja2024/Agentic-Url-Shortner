package io.agentic.orchestrator.dag;

/** Does a node's work (an agent). Throwing fails the node with the exception's message. */
@FunctionalInterface
public interface NodeExecutor {

    NodeOutput execute(NodeContext context) throws Exception;
}

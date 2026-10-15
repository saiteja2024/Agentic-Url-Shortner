package io.agentic.orchestrator.dag;

/**
 * Receives a run's audit trail. Calls are serialised per run, in sequence order. A listener that
 * throws fails the step that produced the event: an unrecorded state change is not allowed to proceed.
 */
public interface RunListener {

    RunListener NONE = new RunListener() {};

    default void onEvent(RunEvent event) {}

    default void onArtifact(Artifact artifact) {}
}

package io.agentic.orchestrator.service;

import java.util.UUID;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(UUID id) {
        super("No run with id " + id);
    }
}

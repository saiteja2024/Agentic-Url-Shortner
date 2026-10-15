package io.agentic.orchestrator.pipeline;

import java.util.List;

/** Paths or actions that always require human approval (enforced by governance policy). */
public record ApprovalRule(String id, List<String> match) {

    public ApprovalRule {
        match = match == null ? List.of() : List.copyOf(match);
    }
}

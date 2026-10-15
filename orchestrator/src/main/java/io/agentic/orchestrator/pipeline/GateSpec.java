package io.agentic.orchestrator.pipeline;

import org.springframework.lang.Nullable;

/**
 * A gate declared in the pipeline. {@code type} selects the implementation (approval, command,
 * check, jacoco, policy); the other fields are type-specific settings.
 */
public record GateSpec(
        String type,
        @Nullable String run,
        @Nullable String description,
        @Nullable Double minLine,
        @Nullable Double minBranch) {

    public static final String APPROVAL = "approval";

    public boolean isApproval() {
        return APPROVAL.equals(type);
    }
}

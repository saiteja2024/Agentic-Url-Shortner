package io.agentic.orchestrator.pipeline;

import io.agentic.orchestrator.dag.DagPlan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural checks before any run (ORCH-002) and cycle detection (ORCH-003). */
public final class PipelineValidator {

    private PipelineValidator() {}

    /**
     * @return the same definition, if valid
     * @throws PipelineDefinitionException listing every problem found
     */
    public static PipelineDefinition validate(PipelineDefinition definition) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<StageSpec> stages = definition.stages();
        for (int i = 0; i < stages.size(); i++) {
            StageSpec stage = stages.get(i);
            if (stage.id() == null || stage.id().isBlank()) {
                problems.add("stage #" + (i + 1) + " has no id");
                continue;
            }
            if (!ids.add(stage.id())) {
                problems.add("duplicate stage id '" + stage.id() + "'");
            }
            if (stage.agent() == null || stage.agent().isBlank()) {
                problems.add("stage '" + stage.id() + "' has no agent");
            }
        }
        for (StageSpec stage : stages) {
            if (stage.id() == null) {
                continue;
            }
            for (String dependency : stage.dependsOn()) {
                if (!ids.contains(dependency)) {
                    problems.add("stage '" + stage.id() + "' depends on unknown stage '" + dependency + "'");
                }
            }
            checkGates(definition, stage, problems);
        }
        if (!problems.isEmpty()) {
            throw new PipelineDefinitionException(problems);
        }
        DagPlan.of(stages); // throws on cycles
        return definition;
    }

    private static void checkGates(PipelineDefinition definition, StageSpec stage, List<String> problems) {
        for (String gate : stage.entryGates()) {
            GateSpec spec = definition.gates().get(gate);
            if (spec == null) {
                problems.add("stage '" + stage.id() + "' references undeclared gate '" + gate + "'");
            } else if (spec.isApproval()) {
                problems.add("stage '" + stage.id() + "' uses approval gate '" + gate
                        + "' as an entry gate; approvals are exit-only");
            }
        }
        for (String gate : stage.exitGates()) {
            if (!definition.gates().containsKey(gate)) {
                problems.add("stage '" + stage.id() + "' references undeclared gate '" + gate + "'");
            }
        }
    }
}

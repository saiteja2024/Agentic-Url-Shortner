package io.agentic.orchestrator.dag;

import io.agentic.orchestrator.pipeline.PipelineDefinitionException;
import io.agentic.orchestrator.pipeline.StageSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The validated graph: Kahn's topological order (ORCH-004) and cycle detection (ORCH-003). Ready
 * nodes are taken in declaration order, so the plan is deterministic.
 */
public final class DagPlan {

    private final List<String> order;
    private final Map<String, List<String>> dependents;

    private DagPlan(List<String> order, Map<String, List<String>> dependents) {
        this.order = List.copyOf(order);
        this.dependents = dependents;
    }

    /** @throws PipelineDefinitionException naming the stages on a cycle */
    public static DagPlan of(List<StageSpec> stages) {
        Map<String, Integer> position = new HashMap<>();
        Map<String, StageSpec> byId = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (StageSpec stage : stages) {
            position.put(stage.id(), position.size());
            byId.put(stage.id(), stage);
            dependents.put(stage.id(), new ArrayList<>());
        }
        for (StageSpec stage : stages) {
            inDegree.put(stage.id(), stage.dependsOn().size());
            for (String dependency : stage.dependsOn()) {
                dependents.get(dependency).add(stage.id());
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparing(position::get));
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : dependents.get(id)) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() < stages.size()) {
            throw new PipelineDefinitionException(List.of("dependency cycle: " + findCycle(byId, order)));
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        dependents.forEach((id, list) -> frozen.put(id, List.copyOf(list)));
        return new DagPlan(order, frozen);
    }

    /** Walks "depends on" edges among the nodes Kahn could not order until a node repeats. */
    private static String findCycle(Map<String, StageSpec> byId, List<String> ordered) {
        Set<String> remaining = new LinkedHashSet<>(byId.keySet());
        ordered.forEach(remaining::remove);
        List<String> path = new ArrayList<>();
        String current = remaining.iterator().next();
        while (!path.contains(current)) {
            path.add(current);
            current = byId.get(current).dependsOn().stream()
                    .filter(remaining::contains)
                    .findFirst()
                    .orElseThrow();
        }
        List<String> cycle = new ArrayList<>(path.subList(path.indexOf(current), path.size()));
        cycle.add(current);
        return String.join(" → ", cycle);
    }

    public List<String> topologicalOrder() {
        return order;
    }

    /** Nodes that directly depend on {@code id}, in declaration order. */
    public List<String> dependents(String id) {
        return dependents.getOrDefault(id, List.of());
    }
}

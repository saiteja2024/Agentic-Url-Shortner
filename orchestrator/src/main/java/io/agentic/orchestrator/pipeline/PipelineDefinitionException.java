package io.agentic.orchestrator.pipeline;

import java.util.List;
import java.util.stream.Collectors;

/** The pipeline definition is unusable; {@link #problems()} lists every reason found. */
public class PipelineDefinitionException extends RuntimeException {

    private final List<String> problems;

    public PipelineDefinitionException(List<String> problems) {
        super(problems.stream().collect(Collectors.joining("\n - ", "Invalid pipeline definition:\n - ", "")));
        this.problems = List.copyOf(problems);
    }

    public PipelineDefinitionException(String problem, Throwable cause) {
        super("Invalid pipeline definition:\n - " + problem, cause);
        this.problems = List.of(problem);
    }

    public List<String> problems() {
        return problems;
    }
}

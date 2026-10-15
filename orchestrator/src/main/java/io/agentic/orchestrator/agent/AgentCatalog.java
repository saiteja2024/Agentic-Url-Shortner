package io.agentic.orchestrator.agent;

import io.agentic.orchestrator.dag.AgentRegistry;
import io.agentic.orchestrator.dag.NodeContext;
import io.agentic.orchestrator.dag.NodeExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** The eight SDLC agents (ORCH-021), each with its role prompt. */
public final class AgentCatalog implements AgentRegistry {

    private static final Map<String, String> ROLES = Map.of(
            "RequirementsAgent",
                    "requirements analyst: turn the request into REQ-nnn requirements with Given/When/Then",
            "ImpactAnalysisAgent", "impact analyst: list the existing files, APIs and data flows the change touches",
            "DesignAgent", "software designer: propose the smallest design that meets the requirements",
            "ImplementAgent", "implementation engineer: change production code under src/main only",
            "TestPlanAgent", "test planner: map every requirement to tests",
            "TestAgent", "test engineer: write tests under src/test only",
            "DocsAgent", "technical writer: update docs/ and README.md",
            "ReleaseAgent", "release manager: write release notes and request the merge with 'ACTION: merge'");

    private final Map<String, LlmAgent> agents;

    public AgentCatalog(LlmClient llm) {
        this.agents =
                Map.copyOf(ROLES.keySet().stream().collect(Collectors.toMap(name -> name, name -> create(name, llm))));
    }

    /** @throws IllegalArgumentException for an agent name outside the catalog */
    public static LlmAgent create(String name, LlmClient llm) {
        String role = ROLES.get(name);
        if (role == null) {
            throw new IllegalArgumentException("unknown agent '" + name + "'");
        }
        return "ImpactAnalysisAgent".equals(name) ? new ImpactAnalysisAgent(llm) : new LlmAgent(name, role, llm);
    }

    @Override
    public NodeExecutor forAgent(String agent) {
        NodeExecutor executor = agents.get(agent);
        if (executor == null) {
            throw new IllegalArgumentException("unknown agent '" + agent + "'");
        }
        return executor;
    }

    /** Brownfield codebase reasoning: every impacted file it names must exist in the workspace. */
    static final class ImpactAnalysisAgent extends LlmAgent {

        private static final Pattern SECTION = Pattern.compile("(?ms)^## Impacted files\\s*\\n(.*?)(?=^## |\\z)");
        private static final Pattern ITEM = Pattern.compile("(?m)^- (\\S+)\\s*$");

        ImpactAnalysisAgent(LlmClient llm) {
            super("ImpactAnalysisAgent", ROLES.get("ImpactAnalysisAgent"), llm);
        }

        @Override
        protected void verify(NodeContext ctx, String reply) {
            Path root = ctx.workspace();
            Matcher section = SECTION.matcher(reply);
            if (root == null || !section.find()) {
                return;
            }
            List<String> missing = new ArrayList<>();
            Matcher item = ITEM.matcher(section.group(1));
            while (item.find()) {
                if (!Files.exists(root.resolve(item.group(1)).normalize())) {
                    missing.add(item.group(1));
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException("impact analysis names files that do not exist: " + missing);
            }
        }
    }
}

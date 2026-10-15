package io.agentic.orchestrator.agent;

import io.agentic.orchestrator.dag.Artifact;
import io.agentic.orchestrator.dag.NodeContext;
import io.agentic.orchestrator.dag.NodeExecutor;
import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.dag.Question;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An SDLC agent: builds a prompt from the node's context, calls the LLM, and turns the reply into
 * workspace file changes, an artifact, clarifying questions and requested actions (ORCH-021).
 *
 * <p>Reply conventions: {@code <<<FILE path ... >>>} blocks are files to write; an
 * {@code ## Open questions} section with {@code - Q1: ...} lines raises questions; an
 * {@code ACTION: merge} line requests an action.
 */
public class LlmAgent implements NodeExecutor {

    private static final Pattern FILE_BLOCK = Pattern.compile("(?ms)^<<<FILE (\\S+)\\n(.*?)^>>>\\s*$\\n?");
    private static final Pattern QUESTIONS = Pattern.compile("(?ms)^## Open questions\\s*\\n(.*?)(?=^## |\\z)");
    private static final Pattern QUESTION = Pattern.compile("(?m)^- (Q\\d+):\\s*(.+)$");
    private static final Pattern ACTION = Pattern.compile("(?m)^ACTION:\\s*([a-z-]+)\\s*$");

    private final String name;
    private final String role;
    private final LlmClient llm;

    public LlmAgent(String name, String role, LlmClient llm) {
        this.name = name;
        this.role = role;
        this.llm = llm;
    }

    public String name() {
        return name;
    }

    @Override
    public NodeOutput execute(NodeContext ctx) throws Exception {
        LlmResponse response = llm.complete(
                new LlmRequest(ctx.scenario(), name, ctx.nodeId(), ctx.attempt(), systemPrompt(), prompt(ctx)));
        String reply = response.text();

        List<String> written = new ArrayList<>();
        Matcher files = FILE_BLOCK.matcher(reply);
        while (files.find()) {
            written.add(write(ctx.workspace(), files.group(1), files.group(2)));
        }
        String narrative = FILE_BLOCK.matcher(reply).replaceAll("").strip();
        verify(ctx, reply);

        StringBuilder artifact = new StringBuilder(narrative);
        if (!written.isEmpty()) {
            artifact.append("\n\nFiles written:\n");
            written.forEach(f -> artifact.append("- ").append(f).append('\n'));
        }
        return new NodeOutput(
                Map.of(ctx.nodeId() + ".md", artifact.toString().strip() + "\n"),
                questions(reply),
                actions(reply),
                response.inputTokens(),
                response.outputTokens());
    }

    /** Hook for agents that must check their reply against the workspace. */
    protected void verify(NodeContext ctx, String reply) throws Exception {}

    protected String systemPrompt() {
        return "You are the " + role + " in a governed software delivery pipeline. "
                + "Work only within your stage. Write files as <<<FILE path ... >>> blocks. "
                + "If the request is ambiguous, list questions under '## Open questions' as '- Q1: ...'.";
    }

    private static String prompt(NodeContext ctx) {
        StringBuilder prompt =
                new StringBuilder("## Request\n").append(ctx.input()).append("\n");
        for (Map.Entry<String, List<Artifact>> upstream : ctx.upstream().entrySet()) {
            for (Artifact artifact : upstream.getValue()) {
                prompt.append("\n## Input: ")
                        .append(upstream.getKey())
                        .append('/')
                        .append(artifact.name())
                        .append(" (sha256 ")
                        .append(
                                artifact.contentHash(),
                                0,
                                Math.min(12, artifact.contentHash().length()))
                        .append(")\n")
                        .append(artifact.content())
                        .append('\n');
            }
        }
        if (!ctx.answers().isEmpty()) {
            prompt.append("\n## Answers to your questions\n");
            ctx.answers()
                    .forEach((q, a) ->
                            prompt.append("- ").append(q).append(": ").append(a).append('\n'));
        }
        if (ctx.feedback() != null) {
            prompt.append("\n## Your previous attempt failed — fix this\n")
                    .append(ctx.feedback())
                    .append('\n');
        }
        return prompt.toString();
    }

    private static String write(Path root, String relative, String content) throws IOException {
        if (root == null) {
            return relative + " (not written: no workspace)";
        }
        Path target = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !target.startsWith(root.normalize())) {
            throw new IllegalArgumentException("refusing to write outside the workspace: " + relative);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return relative;
    }

    private static List<Question> questions(String reply) {
        Matcher section = QUESTIONS.matcher(reply);
        if (!section.find()) {
            return List.of();
        }
        Map<String, Question> found = new LinkedHashMap<>();
        Matcher q = QUESTION.matcher(section.group(1));
        while (q.find()) {
            found.put(q.group(1), new Question(q.group(1), q.group(2).strip()));
        }
        return List.copyOf(found.values());
    }

    private static List<String> actions(String reply) {
        List<String> actions = new ArrayList<>();
        Matcher action = ACTION.matcher(reply);
        while (action.find()) {
            actions.add(action.group(1));
        }
        return actions;
    }
}

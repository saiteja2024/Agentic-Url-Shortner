package io.agentic.orchestrator.gate;

import io.agentic.orchestrator.dag.NodeOutput;
import io.agentic.orchestrator.pipeline.GateSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The gate types a pipeline can declare (ORCH-030, ORCH-008). */
public final class BuiltInGates implements GateRegistry {

    private static final int OUTPUT_TAIL_LINES = 25;
    private static final int OUTPUT_TAIL_CHARS = 2000;
    private static final Pattern REQUIREMENT = Pattern.compile("(?m)^#{2,4}\\s+(REQ-\\d{3})\\b");
    private static final Map<String, Pattern> SECRETS = Map.of(
            "AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            "private key", Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
            "GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36}\\b"),
            "Anthropic API key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{20,}"),
            "Slack token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            "hard-coded credential",
                    Pattern.compile(
                            "(?i)\\b(password|passwd|secret|api[_-]?key|token)\\b\\s*[:=]\\s*\"[^\"\\s]{8,}\""));

    private final Duration commandTimeout;

    public BuiltInGates(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    @Override
    public Gate resolve(String gateId, GateSpec spec) {
        return switch (spec.type()) {
            case GateSpec.APPROVAL -> GateRegistry.defaults().resolve(gateId, spec);
            case "command" -> this::command;
            case "coverage" -> BuiltInGates::coverage;
            case "allowed-paths" -> BuiltInGates::allowedPaths;
            case "secret-scan" -> BuiltInGates::secretScan;
            case "requirements" -> BuiltInGates::requirements;
            default ->
                throw new IllegalArgumentException(
                        "no implementation for gate '" + gateId + "' of type '" + spec.type() + "'");
        };
    }

    /** Runs {@code spec.run} with {@code sh -c} in the workspace. */
    private GateResult command(GateContext ctx) throws IOException, InterruptedException {
        Path root = ctx.workspace().root();
        if (root == null) {
            return GateResult.fail("no workspace to run '" + ctx.spec().run() + "' in");
        }
        Path log = Files.createTempFile("gate-" + ctx.gateId(), ".log");
        try {
            long started = System.nanoTime();
            Process process = new ProcessBuilder("sh", "-c", ctx.spec().run())
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(commandTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return GateResult.fail(
                        "'" + ctx.spec().run() + "' timed out after " + commandTimeout.toSeconds() + "s");
            }
            String seconds = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - started) / 1e9);
            if (process.exitValue() == 0) {
                return GateResult.pass("'" + ctx.spec().run() + "' succeeded in " + seconds);
            }
            return GateResult.fail("'" + ctx.spec().run() + "' exit " + process.exitValue() + ":\n" + tail(log));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static String tail(Path log) throws IOException {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - OUTPUT_TAIL_LINES), lines.size()));
        return tail.length() > OUTPUT_TAIL_CHARS ? tail.substring(tail.length() - OUTPUT_TAIL_CHARS) : tail;
    }

    /** Whole-project JaCoCo line coverage against {@code minLine}. */
    private static GateResult coverage(GateContext ctx) throws IOException {
        Path root = ctx.workspace().root();
        Path report = root == null ? null : root.resolve("target/site/jacoco/jacoco.csv");
        if (report == null || !Files.exists(report)) {
            return GateResult.fail("no JaCoCo report at target/site/jacoco/jacoco.csv; run the tests first");
        }
        long covered = 0;
        long missed = 0;
        List<String> rows = Files.readAllLines(report);
        for (String row : rows.subList(1, rows.size())) {
            String[] cells = row.split(",");
            missed += Long.parseLong(cells[7]);
            covered += Long.parseLong(cells[8]);
        }
        double ratio = covered + missed == 0 ? 0 : (double) covered / (covered + missed);
        double minimum = ctx.spec().minLine() == null ? 0.8 : ctx.spec().minLine();
        String summary =
                String.format(Locale.ROOT, "line coverage %.1f%% (minimum %.1f%%)", ratio * 100, minimum * 100);
        return ratio + 1e-9 >= minimum ? GateResult.pass(summary) : GateResult.fail(summary);
    }

    /** Every changed path must match one of the stage's allowedPaths globs. */
    private static GateResult allowedPaths(GateContext ctx) {
        if (ctx.checkpoint() == null) {
            return GateResult.pass("no workspace");
        }
        List<PathMatcher> allowed = ctx.stage().allowedPaths().stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();
        List<String> outside = ctx.workspace().changedPaths(ctx.checkpoint()).stream()
                .filter(p -> allowed.stream().noneMatch(m -> m.matches(Path.of(p))))
                .toList();
        if (outside.isEmpty()) {
            return GateResult.pass("all changes within " + ctx.stage().allowedPaths());
        }
        return GateResult.fail("changed paths outside " + ctx.stage().allowedPaths() + ": " + outside);
    }

    /** Scans added lines; reports the kind of secret and line number, never the value. */
    private static GateResult secretScan(GateContext ctx) {
        if (ctx.checkpoint() == null) {
            return GateResult.pass("no workspace");
        }
        List<String> findings = new ArrayList<>();
        List<String> added = ctx.workspace().addedLines(ctx.checkpoint());
        for (int i = 0; i < added.size(); i++) {
            for (Map.Entry<String, Pattern> secret : SECRETS.entrySet()) {
                if (secret.getValue().matcher(added.get(i)).find()) {
                    findings.add(secret.getKey() + " in added line " + (i + 1));
                }
            }
        }
        return findings.isEmpty()
                ? GateResult.pass("no secrets in " + added.size() + " added lines")
                : GateResult.fail("possible secrets: " + String.join("; ", findings));
    }

    /** Requirements must carry REQ-nnn ids, each with Given/When/Then. */
    private static GateResult requirements(GateContext ctx) {
        NodeOutput output = ctx.output();
        String text = output == null ? "" : String.join("\n", output.sorted().values());
        Matcher ids = REQUIREMENT.matcher(text);
        List<int[]> sections = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (ids.find()) {
            sections.add(new int[] {ids.start()});
            names.add(ids.group(1));
        }
        if (names.isEmpty()) {
            return GateResult.fail("no REQ-nnn requirements found");
        }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int end = i + 1 < sections.size() ? sections.get(i + 1)[0] : text.length();
            String body = text.substring(sections.get(i)[0], end);
            List<String> missing = new ArrayList<>();
            for (String keyword : List.of("Given", "When", "Then")) {
                if (!body.contains(keyword)) {
                    missing.add(keyword);
                }
            }
            if (!missing.isEmpty()) {
                problems.add(names.get(i) + " lacks " + String.join("/", missing));
            }
        }
        return problems.isEmpty()
                ? GateResult.pass(names.size() + " requirements with acceptance criteria: " + names)
                : GateResult.fail(String.join("; ", problems));
    }
}

package io.agentic.orchestrator.workspace;

import io.agentic.orchestrator.dag.RunWorkspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A run's working copy (ORCH-022): the target project copied into {@code workspaces/<runId>},
 * committed on {@code main}, with the run working on branch {@code run/<runId>}. Uses the git CLI.
 */
public final class GitWorkspace implements RunWorkspace {

    private static final Set<String> NOT_COPIED = Set.of("target", ".git", "node_modules");

    private final Path root;
    private final String branch;

    private GitWorkspace(Path root, String branch) {
        this.root = root;
        this.branch = branch;
    }

    /** @throws IllegalArgumentException if {@code seed} is not a directory */
    public static GitWorkspace create(Path seed, Path workspacesDir, UUID runId) {
        if (!Files.isDirectory(seed)) {
            throw new IllegalArgumentException("target project not found: " + seed);
        }
        Path root = workspacesDir.resolve(runId.toString());
        copy(seed, root);
        GitWorkspace ws = new GitWorkspace(root, "run/" + runId);
        ws.git("init", "-q", "-b", "main");
        ws.git("add", "-A");
        ws.git("commit", "-q", "-m", "baseline: target project");
        ws.git("checkout", "-q", "-b", ws.branch);
        return ws;
    }

    @Override
    public synchronized String checkpoint() {
        return git("rev-parse", "HEAD").trim();
    }

    @Override
    public synchronized void reset(String checkpoint) {
        git("reset", "-q", "--hard", checkpoint);
        git("clean", "-q", "-fd"); // untracked files go; ignored build output (target/) stays for speed
    }

    @Override
    public synchronized String commit(String message) {
        git("add", "-A");
        if (git("status", "--porcelain").isBlank()) {
            return null;
        }
        git("commit", "-q", "-m", message);
        return checkpoint();
    }

    @Override
    public synchronized List<String> changedPaths(String since) {
        git("add", "-A");
        return git("diff", "--cached", "--name-only", since)
                .lines()
                .filter(l -> !l.isBlank())
                .toList();
    }

    @Override
    public synchronized List<String> addedLines(String since) {
        git("add", "-A");
        return git("diff", "--cached", "--unified=0", since)
                .lines()
                .filter(l -> l.startsWith("+") && !l.startsWith("+++"))
                .map(l -> l.substring(1))
                .toList();
    }

    @Override
    public synchronized void merge() {
        git("checkout", "-q", "main");
        git("merge", "-q", "--no-ff", "-m", "Merge " + branch + " (approved)", branch);
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public String branch() {
        return branch;
    }

    /** Runs git in the workspace with a fixed identity and no user/system config. */
    String git(String... args) {
        List<String> command = new ArrayList<>(List.of(
                "git",
                "-c",
                "user.name=agentic-orchestrator",
                "-c",
                "user.email=orchestrator@agentic.local",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null"));
        command.addAll(Arrays.asList(args));
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + args[0] + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output.strip());
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running git", e);
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(from)
                            && NOT_COPIED.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, to.resolve(from.relativize(file).toString()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not copy target project to " + to, e);
        }
    }
}

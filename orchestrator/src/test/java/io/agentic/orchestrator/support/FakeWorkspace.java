package io.agentic.orchestrator.support;

import io.agentic.orchestrator.dag.RunWorkspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** In-memory stand-in for a git working copy: commits are snapshots of a path → content map. */
public final class FakeWorkspace implements RunWorkspace {

    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<Map<String, String>> commits = new ArrayList<>();
    private boolean merged;

    public FakeWorkspace() {
        commits.add(Map.of());
    }

    public synchronized void write(String path, String content) {
        files.put(path, content);
    }

    public synchronized Map<String, String> files() {
        return Map.copyOf(files);
    }

    public synchronized boolean merged() {
        return merged;
    }

    @Override
    public synchronized String checkpoint() {
        return "c" + (commits.size() - 1);
    }

    @Override
    public synchronized void reset(String checkpoint) {
        int index = Integer.parseInt(checkpoint.substring(1));
        files.clear();
        files.putAll(commits.get(index));
        commits.subList(index + 1, commits.size()).clear();
    }

    @Override
    public synchronized String commit(String message) {
        if (files.equals(commits.getLast())) {
            return null;
        }
        commits.add(Map.copyOf(files));
        return checkpoint();
    }

    @Override
    public synchronized List<String> changedPaths(String since) {
        Map<String, String> base = commits.get(Integer.parseInt(since.substring(1)));
        TreeSet<String> all = new TreeSet<>(base.keySet());
        all.addAll(files.keySet());
        return all.stream()
                .filter(p -> !Objects.equals(base.get(p), files.get(p)))
                .toList();
    }

    @Override
    public synchronized List<String> addedLines(String since) {
        return changedPaths(since).stream()
                .map(files::get)
                .filter(Objects::nonNull)
                .flatMap(String::lines)
                .toList();
    }

    @Override
    public synchronized void merge() {
        merged = true;
    }

    @Override
    public Path root() {
        return null;
    }

    @Override
    public String branch() {
        return "run/fake";
    }
}

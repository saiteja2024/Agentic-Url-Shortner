package io.agentic.orchestrator.dag;

import java.nio.file.Path;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * The run's working copy (a git branch, ORCH-022). The engine checkpoints before each node, resets
 * on retry and rollback (ORCH-032/033), and commits each successful node.
 */
public interface RunWorkspace {

    /** No working copy: nothing to checkpoint, change or roll back (used by unit tests). */
    RunWorkspace NONE = new RunWorkspace() {
        @Override
        public @Nullable String checkpoint() {
            return null;
        }

        @Override
        public void reset(String checkpoint) {}

        @Override
        public @Nullable String commit(String message) {
            return null;
        }

        @Override
        public List<String> changedPaths(String since) {
            return List.of();
        }

        @Override
        public List<String> addedLines(String since) {
            return List.of();
        }

        @Override
        public void merge() {}

        @Override
        public @Nullable Path root() {
            return null;
        }

        @Override
        public String branch() {
            return "(none)";
        }
    };

    /** Current commit, or null without a working copy. */
    @Nullable
    String checkpoint();

    /** Discards every change since {@code checkpoint}, committed or not. */
    void reset(String checkpoint);

    /** Commits all changes; returns the new commit, or null if nothing changed. */
    @Nullable
    String commit(String message);

    /** Paths changed since {@code since}, including uncommitted changes. */
    List<String> changedPaths(String since);

    /** Lines added since {@code since}, including uncommitted changes (for secret scanning). */
    List<String> addedLines(String since);

    /** Merges the run branch into main (after the merge approval). */
    void merge();

    @Nullable
    Path root();

    String branch();
}

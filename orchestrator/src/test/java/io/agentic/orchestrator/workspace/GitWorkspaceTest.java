package io.agentic.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceTest {

    @TempDir
    Path tmp;

    private GitWorkspace create() throws Exception {
        Path seed = Files.createDirectories(tmp.resolve("seed/src"));
        Files.writeString(seed.resolve("App.java"), "class App {}\n");
        Files.createDirectories(tmp.resolve("seed/target"));
        Files.writeString(tmp.resolve("seed/target/junk.class"), "build output");
        return GitWorkspace.create(
                tmp.resolve("seed"),
                tmp.resolve("workspaces"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    @Tag("ORCH-022")
    @DisplayName("ORCH-022: each run gets a copy of the target project on its own branch")
    void createsRunBranch() throws Exception {
        GitWorkspace ws = create();

        assertThat(ws.branch()).isEqualTo("run/00000000-0000-0000-0000-000000000001");
        assertThat(ws.root()).isEqualTo(tmp.resolve("workspaces/00000000-0000-0000-0000-000000000001"));
        assertThat(ws.root().resolve("src/App.java")).exists();
        assertThat(ws.root().resolve("target")).doesNotExist(); // build output is not copied
        assertThat(ws.git("rev-parse", "--abbrev-ref", "HEAD").trim()).isEqualTo(ws.branch());
    }

    @Test
    @Tag("ORCH-022")
    @DisplayName("ORCH-022: commits checkpoint each node; changes and added lines are visible since a checkpoint")
    void checkpointsAndDiffs() throws Exception {
        GitWorkspace ws = create();
        String base = ws.checkpoint();
        Files.writeString(ws.root().resolve("src/New.java"), "class New {}\nString key = \"x\";\n");
        Files.writeString(ws.root().resolve("src/App.java"), "class App { int x; }\n");

        assertThat(ws.changedPaths(base)).containsExactlyInAnyOrder("src/App.java", "src/New.java");
        assertThat(ws.addedLines(base)).contains("class New {}", "class App { int x; }");

        String commit = ws.commit("node implement attempt 1");
        assertThat(commit).isNotEqualTo(base);
        assertThat(ws.commit("nothing changed")).isNull();
        assertThat(ws.changedPaths(base)).containsExactlyInAnyOrder("src/App.java", "src/New.java");
    }

    @Test
    @Tag("ORCH-033")
    @DisplayName("ORCH-033: reset discards committed and uncommitted changes since the checkpoint")
    void resetRollsBack() throws Exception {
        GitWorkspace ws = create();
        String base = ws.checkpoint();
        Files.writeString(ws.root().resolve("src/New.java"), "class New {}");
        ws.commit("node a");
        Files.writeString(ws.root().resolve("src/Dirty.java"), "class Dirty {}");

        ws.reset(base);

        assertThat(ws.root().resolve("src/New.java")).doesNotExist();
        assertThat(ws.root().resolve("src/Dirty.java")).doesNotExist();
        assertThat(ws.checkpoint()).isEqualTo(base);
    }

    @Test
    @Tag("ORCH-031")
    @DisplayName("ORCH-031: merge brings the run branch into main")
    void mergesIntoMain() throws Exception {
        GitWorkspace ws = create();
        Files.writeString(ws.root().resolve("src/Feature.java"), "class Feature {}");
        ws.commit("node implement");

        ws.merge();

        assertThat(ws.git("show", "main:src/Feature.java")).contains("class Feature");
    }

    @Test
    @Tag("ORCH-022")
    @DisplayName("ORCH-022: a missing seed project fails clearly")
    void missingSeed() {
        assertThatThrownBy(() -> GitWorkspace.create(tmp.resolve("nope"), tmp.resolve("ws"), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }
}

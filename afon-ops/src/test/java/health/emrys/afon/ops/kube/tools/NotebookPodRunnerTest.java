package health.emrys.afon.ops.kube.tools;

import health.emrys.afon.ops.kube.ExecResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotebookPodRunnerTest {

    private final List<String> calls = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private boolean podHasToken = true;
    /** The run fails with an error of python's, with nothing printed, as it does when the notebook cannot be run at all. */
    private boolean cannotRun;
    /** The runs of the notebooks, as the commands. */
    private final List<String> runs = new ArrayList<>();
    /** The notebooks whose execution fails as the notebook does: its summary fails. */
    private final List<String> failing = new ArrayList<>();
    @TempDir
    Path dir;

    private final NotebookPodRunner.Hub hub = new NotebookPodRunner.Hub() {
        @Override
        public void stopServer() {
            calls.add("stop");
        }

        @Override
        public void signIn() {
            calls.add("sign in");
        }

        @Override
        public String awaitPod(Instant since) {
            calls.add("await pod");
            return "jupyter-smoke-40example-2eorg";
        }
    };

    private final NotebookPodRunner.Pod pod = new NotebookPodRunner.Pod() {
        @Override
        public ExecResult exec(String... command) {
            String text = String.join(" ", command);
            calls.add("exec " + (text.length() > 120 ? text.substring(0, 120) : text));
            if (text.contains("OIDC_TOKEN") && command[0].equals("sh")) {
                return new ExecResult(podHasToken ? 0 : 1, "", "", text, "pod");
            }
            if (command[0].equals("env")) {
                // the run of a notebook: python with the notebook, the file to write and the timeout
                runs.add(List.of(command).toString());
                if (cannotRun) {
                    return new ExecResult(1, "", "Traceback ...\nFileNotFoundError: no such file", text, "pod");
                }
                boolean fails = failing.stream().anyMatch(text::contains);
                return new ExecResult(fails ? 1 : 0, fails ? "FAIL - a step: because of x\n[cell 3] AssertionError: 1 of 2 steps failed\n" : "", "", text, "pod");
            }
            return new ExecResult(0, "", "", text, "pod");
        }

        @Override
        public void copyTo(Path local, String remote) {
            calls.add("copy to " + remote);
        }

        @Override
        public void copyFrom(String remote, Path local) throws java.io.IOException {
            calls.add("copy from " + remote);
            Files.writeString(local, "executed");
        }
    };

    private NotebookPodRunner run() {
        return new NotebookPodRunner(hub, name -> {
            calls.add("pod " + name);
            return pod;
        }, 120, new PrintStream(out));
    }

    @Test
    void signsTheMemberInToANewServerThenRunsEachNotebookInThePodAndBringsBackTheResult() throws Exception {
        List<NotebookPodRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb"), Path.of("/x/two.ipynb")), dir.resolve("results"), Map.of("AFON_TARGET", "diab-2026"));

        assertThat(results).extracting(NotebookPodRunner.Result::passed).containsExactly(true, true);
        assertThat(calls.subList(0, 4)).containsExactly("stop", "sign in", "await pod", "pod jupyter-smoke-40example-2eorg");
        assertThat(calls).contains("copy to " + NotebookPodRunner.DIR + "/one.ipynb", "copy from " + NotebookPodRunner.DIR + "/one.executed.ipynb");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)).contains("env", "AFON_TARGET=diab-2026", "python", "-c", NotebookPodRunner.RUN, NotebookPodRunner.DIR + "/one.ipynb",
                                         NotebookPodRunner.DIR + "/one.executed.ipynb", "120");
        assertThat(Files.readString(dir.resolve("results/one.ipynb"))).isEqualTo("executed");
        assertThat(out.toString()).contains("=== Notebook one.ipynb passed", "=== Notebook two.ipynb passed");
    }

    @Test
    void failsTheNotebookWhoseCellFailedWithWhatItPrintedAndRunsTheNextOneAnyway() throws Exception {
        failing.add("one.executed.ipynb");

        List<NotebookPodRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb"), Path.of("/x/two.ipynb")), dir.resolve("results"), Map.of());

        assertThat(results).extracting(NotebookPodRunner.Result::passed).containsExactly(false, true);
        assertThat(results.get(0).output()).contains("FAIL - a step: because of x");
        assertThat(out.toString()).contains("=== Notebook one.ipynb FAILED", "[cell 3] AssertionError");
    }

    @Test
    void refusesAPodThatHasNoTokenForItWasNotStartedByTheMembersSignIn() {
        podHasToken = false;

        assertThatThrownBy(() -> run().run(List.of(Path.of("/x/one.ipynb")), dir.resolve("results"), Map.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("has no OIDC_TOKEN");
        assertThat(calls).noneMatch(c -> c.startsWith("copy to"));
    }

    @Test
    void saysWhyWhenTheNotebookCannotBeRunAtAllAsNothingElseWouldTell() throws Exception {
        cannotRun = true;

        List<NotebookPodRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb")), dir.resolve("results"), Map.of());

        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).output()).contains("FileNotFoundError");
        assertThat(out.toString()).contains("=== Notebook one.ipynb FAILED");
    }
}

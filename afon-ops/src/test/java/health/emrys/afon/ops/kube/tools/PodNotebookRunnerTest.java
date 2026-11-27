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

class PodNotebookRunnerTest {

    private final List<String> calls = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private boolean podHasToken = true;
    /** The run fails with an error of python's, with nothing printed, as it does when the notebook cannot be run at all. */
    private boolean cannotRun;
    /** The runs of the notebooks, as the commands. */
    private final List<String> runs = new ArrayList<>();
    /** What was on the standard input of each run. */
    private final List<String> inputs = new ArrayList<>();
    /** Whether the pod has the token of a sign-in, as the hub gives it. */
    private boolean signedIn = true;
    /** The notebooks whose execution fails as the notebook does: its summary fails. */
    private final List<String> failing = new ArrayList<>();
    @TempDir
    Path dir;

    private final UserPod.Hub hub = new UserPod.Hub() {
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

        @Override
        public boolean podHasTokenOfSignIn() {
            return signedIn;
        }
    };

    private final UserPod.Pod pod = new UserPod.Pod() {
        @Override
        public ExecResult exec(String... command) {
            String text = String.join(" ", command);
            calls.add("exec " + (text.length() > 120 ? text.substring(0, 120) : text));
            if (text.contains("OIDC_TOKEN") && command[0].equals("sh")) {
                return new ExecResult(podHasToken ? 0 : 1, "", "", text, "pod");
            }
            return new ExecResult(0, "", "", text, "pod");
        }

        @Override
        public ExecResult exec(byte[] stdin, String... command) {
            String text = String.join(" ", command);
            // the run of a notebook: python with the notebook, the file to write and the timeout, and the environment on its standard input
            runs.add(List.of(command).toString());
            inputs.add(new String(stdin, java.nio.charset.StandardCharsets.UTF_8));
            calls.add("run " + command[command.length - 3]);
            if (cannotRun) {
                return new ExecResult(1, "", "Traceback ...\nFileNotFoundError: no such file", text, "pod");
            }
            boolean fails = failing.stream().anyMatch(text::contains);
            return new ExecResult(fails ? 1 : 0, fails ? "FAIL - a step: because of x\n[cell 3] AssertionError: 1 of 2 steps failed\n" : "", "", text, "pod");
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

    private PodNotebookRunner run() {
        return new PodNotebookRunner(hub, name -> {
            calls.add("pod " + name);
            return pod;
        }, 120, new PrintStream(out));
    }

    @Test
    void signsTheMemberInToANewServerThenRunsEachNotebookInThePodAndBringsBackTheResult() throws Exception {
        List<PodNotebookRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb"), Path.of("/x/two.ipynb")), dir.resolve("results"), Map.of("AFON_TARGET", "diab-2026"));

        assertThat(results).extracting(PodNotebookRunner.Result::passed).containsExactly(true, true);
        assertThat(calls.subList(0, 4)).containsExactly("stop", "sign in", "await pod", "pod jupyter-smoke-40example-2eorg");
        assertThat(calls).contains("copy to " + PodNotebookRunner.DIR + "/one.ipynb", "copy from " + PodNotebookRunner.DIR + "/one.executed.ipynb");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0)).contains("python", "-c", PodNotebookRunner.RUN, PodNotebookRunner.DIR + "/one.ipynb", PodNotebookRunner.DIR + "/one.executed.ipynb", "120")
                               .doesNotContain("AFON_TARGET=diab-2026").doesNotContain("diab-2026");
        // the environment is on the standard input, as its size, a line break and the JSON
        assertThat(inputs.get(0)).isEqualTo("{\"AFON_TARGET\":\"diab-2026\"}".length() + "\n{\"AFON_TARGET\":\"diab-2026\"}");
        assertThat(Files.readString(dir.resolve("results/one.ipynb"))).isEqualTo("executed");
        assertThat(out.toString()).contains("=== Notebook one.ipynb passed", "=== Notebook two.ipynb passed");
    }

    @Test
    void failsTheNotebookWhoseCellFailedWithWhatItPrintedAndRunsTheNextOneAnyway() throws Exception {
        failing.add("one.executed.ipynb");

        List<PodNotebookRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb"), Path.of("/x/two.ipynb")), dir.resolve("results"), Map.of());

        assertThat(results).extracting(PodNotebookRunner.Result::passed).containsExactly(false, true);
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

        List<PodNotebookRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb")), dir.resolve("results"), Map.of());

        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).output()).contains("FileNotFoundError");
        assertThat(out.toString()).contains("=== Notebook one.ipynb FAILED");
    }

    @Test
    void doesNotCheckForTheTokenOfASignInInAPodThatTheHubStartedWithNone() throws Exception {
        signedIn = false;
        podHasToken = false;

        List<PodNotebookRunner.Result> results = run().run(List.of(Path.of("/x/one.ipynb")), dir.resolve("results"), Map.of("OIDC_TOKEN", "the-token"));

        assertThat(results.get(0).passed()).isTrue();
        // the token is given to the notebook on the standard input, and is in no command line
        assertThat(inputs.get(0)).contains("the-token");
        assertThat(runs.get(0)).doesNotContain("the-token");
        assertThat(calls).noneMatch(c -> c.contains("OIDC_TOKEN"));
    }

    @Test
    void sendsTheSizeOfTheEnvironmentInBytesThenTheEnvironmentSoThatNothingWaitsForTheEndOfTheInput() throws Exception {
        byte[] input = PodNotebookRunner.input(Map.of("NAME", "caf\u00e9"));

        String text = new String(input, java.nio.charset.StandardCharsets.UTF_8);
        int size = Integer.parseInt(text.substring(0, text.indexOf('\n')));
        assertThat(input.length - (text.indexOf('\n') + 1)).isEqualTo(size);
    }
}

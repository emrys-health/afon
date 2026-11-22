package health.emrys.afon.ops;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code scripts/kube/user-cp.sh} finds the notebook pod of a user and runs {@code kubectl cp}: run here by bash with a {@code kubectl} that lists the pods
 * it is given and records the copies, so that nothing reaches a cluster.
 */
class KubeUserCpScriptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    private Path pods;
    private Path calls;

    @BeforeEach
    void stubKubectl() throws IOException {
        pods = dir.resolve("pods");
        calls = dir.resolve("calls");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, "#!/usr/bin/env bash\n"
                                   + "case \"$*\" in\n"
                                   + "  *'get pods'*) cat '" + pods + "' ;;\n"
                                   + "  *' cp '*|'cp '*) echo \"$*\" >> '" + calls + "' ;;\n"
                                   + "esac\n");
        kubectl.toFile().setExecutable(true);
    }

    private record Result(int status, String output) {
    }

    private Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("bash", ROOT + "/scripts/kube/user-cp.sh"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).directory(ROOT.resolve("scripts").toFile());
        // as afon-cli runs a script: from the scripts dir, with the dir that it was run from in AFON_CALLER_DIR
        builder.environment().put("AFON_CALLER_DIR", dir.toString());
        builder.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), out);
    }

    private List<String> copies() throws IOException {
        return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
    }

    @Test
    void copiesToTheOnlyNotebookPodWhateverTheUser() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-mauro-2etalevi-40emrys-2ehealth\n");
        Files.writeString(dir.resolve("a.ipynb"), "{}");
        Result result = run("to", "a.ipynb");
        assertThat(result.status()).as(result.output()).isZero();
        assertThat(copies()).containsExactly("cp -n default " + dir + "/a.ipynb jupyter-mauro-2etalevi-40emrys-2ehealth:/home/jovyan/a.ipynb");
    }

    @Test
    void findsAUserByTheirEmailAsTheyWouldBeEscapedInThePodsName() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob-40emrys-2ehealth\njupyter-mauro-2etalevi-40emrys-2ehealth\n");
        Files.createDirectories(dir.resolve("out"));
        Result result = run("--user", "mauro.talevi@emrys.health", "from", "results/a.ipynb", "out/");
        assertThat(result.status()).as(result.output()).isZero();
        assertThat(copies()).containsExactly("cp -n default jupyter-mauro-2etalevi-40emrys-2ehealth:/home/jovyan/results/a.ipynb " + dir + "/out/a.ipynb");
    }

    @Test
    void keepsAnAbsolutePathOfThePodAndCopiesToTheCurrentDirByDefault() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob\n");
        assertThat(run("from", "/tmp/x.csv").status()).isZero();
        assertThat(copies()).containsExactly("cp -n default jupyter-bob:/tmp/x.csv " + dir + "/x.csv");
    }

    @Test
    void asksForTheUserWhenThereAreSeveralPods() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob\njupyter-alice\n");
        Files.writeString(dir.resolve("a.ipynb"), "{}");
        Result result = run("to", "a.ipynb");
        assertThat(result.status()).isNotZero();
        assertThat(result.output()).contains("More than one notebook pod", "jupyter-bob", "jupyter-alice");
        assertThat(copies()).isEmpty();
    }

    @Test
    void failsWhenNoPodIsTheUsers() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob\n");
        Files.writeString(dir.resolve("a.ipynb"), "{}");
        Result result = run("--user", "carol", "to", "a.ipynb");
        assertThat(result.status()).isNotZero();
        assertThat(result.output()).contains("No running notebook pod for 'carol'");
        assertThat(copies()).isEmpty();
    }

    @Test
    void copiesAnAbsoluteLocalPathAsItIs() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob\n");
        Path file = Files.writeString(dir.resolve("b.ipynb"), "{}");
        assertThat(run("to", file.toString(), "notebooks/").status()).isZero();
        assertThat(copies()).containsExactly("cp -n default " + file + " jupyter-bob:/home/jovyan/notebooks/");
    }

    @Test
    void copiesFromToANewFileNameInTheCurrentDir() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob\n");
        assertThat(run("from", "a.ipynb", "renamed.ipynb").status()).isZero();
        assertThat(copies()).containsExactly("cp -n default jupyter-bob:/home/jovyan/a.ipynb " + dir + "/renamed.ipynb");
    }

    @Test
    void failsWhenTheFileToCopyDoesNotExist() throws IOException, InterruptedException {
        Files.writeString(pods, "jupyter-bob\n");
        Result result = run("to", "missing.ipynb");
        assertThat(result.status()).isNotZero();
        assertThat(result.output()).contains("No such file or directory: missing.ipynb");
        assertThat(copies()).isEmpty();
    }
}

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
 * {@code scripts/project/pods.sh} lists the user pods of a project and deletes those of a user, or of every user: run here by bash with a {@code kubectl}
 * that lists the pods it is given and records the deletions, so that nothing reaches a cluster.
 */
class ProjectPodsScriptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static final String PODS = """
        jupyter-alice-40emrys-2ehealth   1/1   Running   0   4h
        jupyter-bob-40emrys-2ehealth     1/1   Running   0   3h
        """;

    @TempDir
    Path dir;

    private Path calls;

    @BeforeEach
    void stubKubectl() throws IOException {
        calls = dir.resolve("calls");
        Path pods = dir.resolve("pods");
        Files.writeString(pods, PODS);
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path kubectl = bin.resolve("kubectl");
        Files.writeString(kubectl, "#!/usr/bin/env bash\n"
                                   + "case \"$*\" in\n"
                                   + "  *'get pods'*) cat '" + pods + "' ;;\n"
                                   + "  *'delete pod'*) echo \"$*\" >> '" + calls + "' ;;\n"
                                   + "esac\n");
        kubectl.toFile().setExecutable(true);
    }

    private record Result(int status, String output) {
    }

    private Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("bash", ROOT + "/scripts/project/pods.sh"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                                                            .directory(ROOT.resolve("scripts").toFile());
        builder.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), out);
    }

    private List<String> deletions() throws IOException {
        return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
    }

    @Test
    void listsTheUserPodsWithTheirUsersByDefault() throws IOException, InterruptedException {
        Result result = run();
        assertThat(result.status()).as(result.output()).isZero();
        assertThat(result.output()).contains("alice@emrys.health", "jupyter-alice-40emrys-2ehealth", "bob@emrys.health", "Running");
        assertThat(deletions()).isEmpty();
    }

    @Test
    void deleteNeedsAUserOrAll() throws IOException, InterruptedException {
        Result result = run("delete");
        assertThat(result.status()).isNotZero();
        assertThat(result.output()).contains("--user", "--all");
        assertThat(deletions()).isEmpty();
    }

    @Test
    void deletesTheUsersPodByTheirEmailAsEscapedInTheName() throws IOException, InterruptedException {
        Result result = run("delete", "--user", "alice@emrys.health", "--yes");
        assertThat(result.status()).as(result.output()).isZero();
        assertThat(deletions()).containsExactly("delete pod -n default jupyter-alice-40emrys-2ehealth --ignore-not-found");
        assertThat(result.output()).contains("open the TRE's URL again");
    }

    @Test
    void deletesEveryPodWithAll() throws IOException, InterruptedException {
        assertThat(run("delete", "--all", "--yes").status()).isZero();
        assertThat(deletions()).containsExactly("delete pod -n default jupyter-alice-40emrys-2ehealth --ignore-not-found",
                                                "delete pod -n default jupyter-bob-40emrys-2ehealth --ignore-not-found");
    }

    @Test
    void dryRunDeletesNothing() throws IOException, InterruptedException {
        Result result = run("delete", "--all", "--dry-run");
        assertThat(result.status()).as(result.output()).isZero();
        assertThat(result.output()).contains("not deleting 2 pod(s)");
        assertThat(deletions()).isEmpty();
    }

    @Test
    void doesNotDeleteWithoutATerminalToAskOnUnlessGivenYes() throws IOException, InterruptedException {
        Result result = run("delete", "--all");
        assertThat(result.status()).isNotZero();
        assertThat(result.output()).contains("give --yes");
        assertThat(deletions()).isEmpty();
    }

    @Test
    void saysSoWhenNoPodIsTheUsers() throws IOException, InterruptedException {
        Result result = run("delete", "--user", "carol", "--yes");
        assertThat(result.status()).isZero();
        assertThat(result.output()).contains("No user pods for 'carol'");
        assertThat(deletions()).isEmpty();
    }

    @Test
    void refusesAUserAndAllTogether() throws IOException, InterruptedException {
        Result result = run("delete", "--user", "bob", "--all", "--yes");
        assertThat(result.status()).isNotZero();
        assertThat(deletions()).isEmpty();
    }
}

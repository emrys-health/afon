package health.emrys.afon.ops.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessExecutorTest {

    @TempDir
    Path dir;

    /** A launcher that records its args and the env switches it was given, and exits with the status in its first arg after them. */
    private Path launcher() throws IOException {
        Path script = dir.resolve("afon-cli.sh");
        Files.writeString(script, "#!/usr/bin/env bash\n"
                                  + "{ echo \"args=$*\"; echo \"AFON_ENV=${AFON_ENV:-unset}\"; echo \"AFON_CONFIG_ENV=${AFON_CONFIG_ENV:-unset}\";\n"
                                  + "  echo \"AFON_CDKTF_ENV=${AFON_CDKTF_ENV:-unset}\"; echo \"EXTRA=${EXTRA:-unset}\"; } > \"" + dir.resolve("seen") + "\"\n"
                                  + "exit \"${EXIT:-0}\"\n");
        script.toFile().setExecutable(true);
        return script;
    }

    @Test
    void runsACommandAndReturnsItsStatus() throws Exception {
        Path launcher = launcher();
        assertThat(new ProcessExecutor().run(List.of(launcher.toString(), "manage-identity", "create", "p"), Map.of())).isZero();
        assertThat(Files.readString(dir.resolve("seen"))).contains("args=manage-identity create p");
        assertThat(new ProcessExecutor().run(List.of(launcher.toString(), "x"), Map.of("EXIT", "3"))).isEqualTo(3);
    }

    @Test
    void doesNotPassTheEnvSwitchesOfTheDeployerToACommandOfTheProjectHome() {
        ProcessBuilder inherits = new ProcessBuilder("x", "--home", "/h", "manage-stack");
        inherits.environment().putAll(Map.of("AFON_ENV", "dev", "AFON_CONFIG_ENV", "dev", "AFON_CDKTF_ENV", "dev", "AFON_LOGGING", "DEBUG"));
        ProcessExecutor.withoutEnvSwitches(inherits, List.of("x", "--home", "/h", "manage-stack"));
        assertThat(inherits.environment()).doesNotContainKeys("AFON_ENV", "AFON_CONFIG_ENV", "AFON_CDKTF_ENV").containsEntry("AFON_LOGGING", "DEBUG");
    }

    @Test
    void keepsTheEnvSwitchesForACommandOfTheDeployersOwnHome() {
        ProcessBuilder inherits = new ProcessBuilder("x", "manage-identity");
        inherits.environment().put("AFON_ENV", "dev");
        ProcessExecutor.withoutEnvSwitches(inherits, List.of("x", "manage-identity"));
        assertThat(inherits.environment()).containsEntry("AFON_ENV", "dev");
    }

    @Test
    void pointsKubectlOfACommandOfAProjectHomeAtTheClusterOfTheProject() throws IOException {
        Path kube = Files.createDirectories(dir.resolve("cdktf-lon/kube"));
        Path config = Files.writeString(kube.resolve("azure-polaris.config"), "x");
        Files.createSymbolicLink(kube.resolve("current.config"), config);
        List<String> command = List.of("x", "--home", "/h", "--cdktf", dir.resolve("cdktf-lon").toString(), "kube-polaris-whitelist", "azure", "set");
        ProcessBuilder inherits = new ProcessBuilder(command);
        inherits.environment().put("KUBECONFIG", "/the/deployers/kubeconfig");

        ProcessExecutor.withKubeconfigOfTheProject(inherits, command);

        // the only kubeconfig of the project, not the link to it, and not the deployer's
        assertThat(inherits.environment()).containsEntry("KUBECONFIG", config.toString());
    }

    @Test
    void takesTheKubeconfigThatCurrentLinksToWhenTheProjectHasSeveral() throws IOException {
        Path kube = Files.createDirectories(dir.resolve("cdktf-p/kube"));
        Files.writeString(kube.resolve("a-stack.config"), "x");
        Path second = Files.writeString(kube.resolve("b-stack.config"), "x");
        Files.createSymbolicLink(kube.resolve("current.config"), second);

        assertThat(ProcessExecutor.kubeconfigOf(List.of("x", "--home", "/h", "--cdktf", dir.resolve("cdktf-p").toString()))).isEqualTo(kube.resolve("current.config"));
    }

    @Test
    void leavesKubectlAloneWhereTheProjectHasNoKubeconfigOrTheCommandIsNotOfAProjectHome() throws IOException {
        // no cdktf dir, a cdktf dir with no kube dir, a kube dir with none, and several with no link to say which
        assertThat(ProcessExecutor.kubeconfigOf(List.of("x", "--home", "/h"))).isNull();
        assertThat(ProcessExecutor.kubeconfigOf(List.of("x", "--home", "/h", "--cdktf", dir.resolve("none").toString()))).isNull();
        Path kube = Files.createDirectories(dir.resolve("cdktf-q/kube"));
        assertThat(ProcessExecutor.kubeconfigOf(List.of("x", "--home", "/h", "--cdktf", dir.resolve("cdktf-q").toString()))).isNull();
        Files.writeString(kube.resolve("a.config"), "x");
        Files.writeString(kube.resolve("b.config"), "x");
        assertThat(ProcessExecutor.kubeconfigOf(List.of("x", "--home", "/h", "--cdktf", dir.resolve("cdktf-q").toString()))).isNull();

        ProcessBuilder own = new ProcessBuilder("x", "manage-identity");
        own.environment().put("KUBECONFIG", "/the/deployers/kubeconfig");
        ProcessExecutor.withKubeconfigOfTheProject(own, List.of("x", "manage-identity", "--cdktf", dir.resolve("cdktf-q").toString()));
        assertThat(own.environment()).containsEntry("KUBECONFIG", "/the/deployers/kubeconfig");
    }

    @Test
    void failsToRunACommandThatIsNotThere() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProcessExecutor().run(List.of(dir.resolve("missing.sh").toString()), Map.of()))
            .isInstanceOf(IOException.class);
    }
}

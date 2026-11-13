package health.emrys.afon.ops.steps;

import health.emrys.afon.config.AfonCloud;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepActionsCheckLoginTest {

    private final List<String> commands = new ArrayList<>();

    private StepActions actionsExiting(int exit) {
        return new StepActions() {
            @Override
            public int run(String command, Path path, OutputStream stdOut, String... args) {
                commands.add(command + " " + String.join(" ", args));
                return exit;
            }
        };
    }

    @Test
    void checksTheCredentialsOfEachCloud() throws Exception {
        for (AfonCloud cloud : AfonCloud.values()) {
            assertThatCode(() -> actionsExiting(0).checkLogin(cloud)).doesNotThrowAnyException();
        }
        assertThat(commands).containsExactly(
            "bash -c $*>/dev/null bash aws sts get-caller-identity",
            "bash -c $*>/dev/null bash az account get-access-token --output none",
            "bash -c $*>/dev/null bash gcloud auth print-access-token");
    }

    @Test
    void failsNamingTheLoginCommandIfNotLoggedIn() {
        assertThatThrownBy(() -> actionsExiting(1).checkLogin(AfonCloud.GCP))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Not logged in to GCP (gcloud auth print-access-token failed): "
                        + "log in with afon-cli.sh gcp-login (or gcp-login-as-service-account), then rerun.");
        assertThatThrownBy(() -> actionsExiting(1).checkLogin(AfonCloud.AWS))
            .hasMessageContaining("Not logged in to AWS").hasMessageContaining("afon-cli.sh aws-login");
        assertThatThrownBy(() -> actionsExiting(1).checkLogin(AfonCloud.AZURE))
            .hasMessageContaining("Not logged in to AZURE").hasMessageContaining("azure-login-app");
    }

    /** Runs the real process, to check that the runner passes the script to bash intact, and that a failing check fails it. */
    @Test
    void discardingScriptRunsTheCheckAndFailsWithIt() throws Exception {
        StepActions actions = new StepActions();
        assertThat(actions.run("bash", null, OutputStream.nullOutputStream(), "-c", "$*>/dev/null", "bash", "echo", "a secret")).isZero();
        assertThatThrownBy(() -> actions.run("bash", null, OutputStream.nullOutputStream(), "-c", "$*>/dev/null", "bash", "false"))
            .isInstanceOf(ch.vorburger.exec.ManagedProcessException.class);
    }
}

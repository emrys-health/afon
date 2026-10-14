package health.emrys.afon.ops.steps;

import com.sun.net.httpserver.HttpServer;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static health.emrys.afon.config.AfonCloud.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepActionsTest {

    @TempDir
    Path cdktfDir;

    /**
     * The commands run, eg {@code tofu destroy --auto-approve}.
     */
    private final List<String> commands = new ArrayList<>();

    private final StepActions actions = new StepActions() {
        @Override
        public int run(String command, Path path, OutputStream stdOut, String... args) {
            commands.add(command + " " + String.join(" ", args));
            return 0;
        }
    };

    @Test
    void canFindStacksInCloudFromStackDirsAndTfStates() throws IOException {
        createDirs("stacks/aws", "stacks/aws-keycloak", "stacks/azure-polaris", "stacks/awsome");
        createFiles("terraform.aws.tfstate", "terraform.aws-polaris.tfstate", "terraform.aws-polaris.tfstate.backup",
                    "terraform.aws-keycloak.tfstate", "terraform.gcp-polaris.tfstate");

        assertThat(StepActions.stackNamesIn(AWS, cdktfDir)).containsExactly("aws", "aws-keycloak", "aws-polaris");
        assertThat(StepActions.stackNamesIn(AZURE, cdktfDir)).containsExactly("azure-polaris");
        assertThat(StepActions.stackNamesIn(GCP, cdktfDir)).containsExactly("gcp-polaris");
    }

    @Test
    void onlyStacksWithTfStateAreDeployed() throws IOException {
        createDirs("stacks/aws", "stacks/gcp", "stacks/aws-keycloak", "stacks/aws-polaris");
        createFiles("terraform.aws-keycloak.tfstate", "terraform.gcp.tfstate");

        assertThat(actions.deployedStackNames(List.of("aws", "aws-keycloak", "aws-polaris"), cdktfDir))
            .containsExactly("aws-keycloak");
        assertThat(actions.deployedStackNames(List.of("gcp"), cdktfDir)).containsExactly("gcp");
    }

    @Test
    void canFindNoStacksInEmptyOrMissingCdktfDir() throws IOException {
        assertThat(StepActions.stackNamesIn(AWS, cdktfDir)).isEmpty();
        assertThat(StepActions.stackNamesIn(AWS, cdktfDir.resolve("missing"))).isEmpty();
    }

    @Test
    void canDeleteStackWithTfState() throws IOException {
        Path stackDir = createDirs("stacks/aws-keycloak").get(0);
        Path tfState = createFiles("terraform.aws-keycloak.tfstate").get(0);

        actions.deleteStack(stackDir, tfState, 1, OutputStream.nullOutputStream());

        assertThat(commands).containsExactly("tofu init", "tofu destroy --auto-approve");
        assertThat(stackDir).doesNotExist();
        assertThat(tfState).doesNotExist();
    }

    @Test
    void deletingStackKeepsWhatOutlivesIt() throws IOException {
        Path stackDir = createDirs("stacks/aws-full").get(0);
        Path tfState = createFiles("terraform.aws-full.tfstate").get(0);
        Files.writeString(stackDir.resolve("cdk.tf.json"), """
            {"resource": {
              "aws_ebs_volume": {"retained_gitea_volume": {"lifecycle": {"prevent_destroy": true}}},
              "aws_eks_cluster": {"cluster": {"lifecycle": {"prevent_destroy": false}}},
              "helm_release": {"gitea": {}}
            }}""");

        actions.deleteStack(stackDir, tfState, 1, OutputStream.nullOutputStream());

        assertThat(commands).containsExactly("tofu init",
                                             "tofu destroy --auto-approve -exclude=aws_ebs_volume.retained_gitea_volume");
    }

    @Test
    void canDeleteStackWithoutTfStateWithoutRunningTofu() throws IOException {
        Path stackDir = createDirs("stacks/aws-keycloak").get(0);

        actions.deleteStack(stackDir, cdktfDir.resolve("terraform.aws-keycloak.tfstate"), 1, OutputStream.nullOutputStream());

        assertThat(commands).isEmpty();
        assertThat(stackDir).doesNotExist();
    }

    @Test
    void cannotDeleteStackWithTfStateButNoStackDir() throws IOException {
        Path tfState = createFiles("terraform.aws-keycloak.tfstate").get(0);

        assertThatThrownBy(() -> actions.deleteStack(cdktfDir.resolve("stacks/aws-keycloak"), tfState, 1,
                                                     OutputStream.nullOutputStream()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("validate-stack");
        assertThat(commands).isEmpty();
        assertThat(tfState).exists();
    }

    @Test
    void refusesToDeleteAnyStackIfOneMustBeKept() {
        StackFilter filter = new StackFilter(
            "stacks conflicting with aws-polaris", name -> true,
            name -> switch (name) {
                case "aws-polaris" -> null;
                case "aws-unknown" -> new StackFilter.Refusal("stack aws-unknown is running, whose resources are unknown", null);
                default -> new StackFilter.Refusal("stack " + name + " is running and already deploys Polaris at x (X)",
                                                   "afon-cli.sh manage-stack delete " + name);
            },
            StackId.parse("aws-polaris"));
        actions.checkNoRefusal(filter, List.of("aws-polaris"));
        assertThatThrownBy(() -> actions.checkNoRefusal(filter, List.of("aws-polaris", "aws-full")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(String.format("""
                Cannot deploy aws-polaris, as stack aws-full is running and already deploys Polaris at x (X).
                To deploy aws-polaris, delete it first:
                    afon-cli.sh manage-stack delete aws-full
                or rerun with --replace to delete it as part of this run."""));
        assertThatThrownBy(() -> actions.checkNoRefusal(filter, List.of("aws-unknown")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageEndingWith("To deploy aws-polaris, rerun with --replace to delete it as part of this run.");
        assertThat(commands).isEmpty();
    }

    @Test
    void canCheckUrlAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/up", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        String url = "http://localhost:" + server.getAddress().getPort();
        try {
            actions.checkAnswers(url + "/up", "Deploy it first");
            assertThatThrownBy(() -> actions.checkAnswers(url + "/missing", "Deploy it first"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(url + "/missing answered 404. Deploy it first");
        } finally {
            server.stop(0);
        }
        assertThatThrownBy(() -> actions.checkAnswers(url + "/up", "Deploy it first"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith(url + "/up cannot be reached")
            .hasMessageEndingWith("Deploy it first");
    }

    private List<Path> createDirs(String... dirs) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String dir : dirs) {
            paths.add(Files.createDirectories(cdktfDir.resolve(dir)));
        }
        return paths;
    }

    private List<Path> createFiles(String... files) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String file : files) {
            paths.add(Files.createFile(cdktfDir.resolve(file)));
        }
        return paths;
    }

}

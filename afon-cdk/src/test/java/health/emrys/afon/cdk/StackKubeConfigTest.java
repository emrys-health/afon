package health.emrys.afon.cdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every stack, of every cloud, has a kubeconfig of its own, {@code $AFON_CDKTF/kube/<stack-id>.config}, the only place its
 * credentials are written, so that stacks deployed at the same time never switch each other's current kubectl context.
 * Every local-exec command of the stack uses it, via {@code KUBECONFIG}.
 */
class StackKubeConfigTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // the cdktf dir of the synthesis, see SynthesisSupport
    private static String kubeConfig(String stack) {
        return "/golden/cdktf/kube/" + stack + ".config";
    }

    private static JsonNode plan(String stack, Path dir) throws Exception {
        // which the monitoring of gcp-full needs, for Loki to write its logs
        return JSON.readTree(SynthesisSupport.synthesise(
            StackId.parse(stack), dir, Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\"}")));
    }

    /** The local-exec provisioners of every resource of the plan. */
    private static List<JsonNode> localExecs(JsonNode plan) {
        List<JsonNode> localExecs = new ArrayList<>();
        for (JsonNode type : plan.path("resource")) {
            for (JsonNode resource : type) {
                for (JsonNode provisioner : resource.path("provisioner")) {
                    if (provisioner.has("local-exec")) {
                        localExecs.add(provisioner.path("local-exec"));
                    }
                }
            }
        }
        return localExecs;
    }

    private static List<String> commands(JsonNode plan) {
        return localExecs(plan).stream().map(localExec -> localExec.path("command").asText()).toList();
    }

    private void everyCommandUsesTheKubeConfigOfTheStackAndNoneTheDefaultOne(String stack, Path dir) throws Exception {
        JsonNode plan = plan(stack, dir);

        List<JsonNode> localExecs = localExecs(plan);
        assertThat(localExecs).isNotEmpty();
        for (JsonNode localExec : localExecs) {
            assertThat(localExec.path("environment").path("KUBECONFIG").asText())
                .as("KUBECONFIG of: %s", localExec.path("command").asText()).isEqualTo(kubeConfig(stack));
        }
        assertThat(commands(plan)).noneMatch(command -> command.contains(".kube/config"));
    }

    @Test
    void everyGcpCommandUsesTheKubeConfigOfTheStack(@TempDir Path dir) throws Exception {
        everyCommandUsesTheKubeConfigOfTheStackAndNoneTheDefaultOne("gcp-full", dir);
    }

    @Test
    void everyAzureCommandUsesTheKubeConfigOfTheStack(@TempDir Path dir) throws Exception {
        everyCommandUsesTheKubeConfigOfTheStackAndNoneTheDefaultOne("azure-full", dir);
    }

    @Test
    void everyAwsCommandUsesTheKubeConfigOfTheStack(@TempDir Path dir) throws Exception {
        everyCommandUsesTheKubeConfigOfTheStackAndNoneTheDefaultOne("aws-full", dir);
    }

    @Test
    void gcpWritesItsCredentialsToTheKubeConfigOfTheStackAndLinksItAsCurrent(@TempDir Path dir) throws Exception {
        String command = commands(plan("gcp-full", dir)).stream()
                                                        .filter(c -> c.contains("get-credentials")).findFirst().orElseThrow();

        assertThat(command).contains("mkdir -p /golden/cdktf/kube")
                           .contains("KUBECONFIG=" + kubeConfig("gcp-full") + " gcloud container clusters get-credentials")
                           .contains("ln -sfn " + kubeConfig("gcp-full") + " /golden/cdktf/kube/current.config");
    }

    @Test
    void azureWritesItsCredentialsToTheKubeConfigOfTheStackAndLinksItAsCurrent(@TempDir Path dir) throws Exception {
        List<String> credentials = commands(plan("azure-full", dir)).stream()
                                                                     .filter(c -> c.contains("az aks get-credentials")).toList();

        // when the cluster is created, and when waiting for the cluster admin role
        assertThat(credentials).hasSize(2);
        assertThat(credentials).allSatisfy(command -> assertThat(command)
            .contains("mkdir -p /golden/cdktf/kube").contains("--file " + kubeConfig("azure-full")).contains("--overwrite-existing"));
        assertThat(credentials).anySatisfy(command -> assertThat(command)
            .contains("ln -sfn " + kubeConfig("azure-full") + " /golden/cdktf/kube/current.config"));
    }

    @Test
    void awsLinksItsKubeConfigAsCurrent(@TempDir Path dir) throws Exception {
        String command = commands(plan("aws-full", dir)).stream()
                                                        .filter(c -> c.contains("update-kubeconfig")).findFirst().orElseThrow();

        assertThat(command).contains("--kubeconfig " + kubeConfig("aws-full"))
                           .contains("ln -sfn " + kubeConfig("aws-full") + " /golden/cdktf/kube/current.config");
    }

    @Test
    void theKubeConfigsOfTwoStacksOfDifferentCloudsDiffer(@TempDir Path dir) throws Exception {
        List<String> gcp = commands(plan("gcp-full", dir.resolve("gcp")));
        List<String> azure = commands(plan("azure-full", dir.resolve("azure")));

        assertThat(gcp).noneMatch(command -> command.contains(kubeConfig("azure-full")));
        assertThat(azure).noneMatch(command -> command.contains(kubeConfig("gcp-full")));
    }
}

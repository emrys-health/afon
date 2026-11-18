package health.emrys.afon.ops.kube;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The kubectl context that a client acts on is checked to be a cluster of the cloud, and, unless it is the cluster of another project's, of this env.
 */
class KubeClientContextTest {

    private static final String IDENTITY_CLUSTER = "arn:aws:eks:eu-west-2:111111111111:cluster/keycloak-idp-cluster";

    @TempDir
    Path dir;

    private KubeClient client(String context, String clusterName) throws IOException {
        Path kubeConfig = dir.resolve("kubeconfig");
        Files.writeString(kubeConfig, """
            apiVersion: v1
            kind: Config
            current-context: %s
            contexts:
              - name: %s
                context: {cluster: c, user: u}
            clusters:
              - name: c
                cluster: {server: "https://localhost:1"}
            users:
              - name: u
                user: {token: x}
            """.formatted(context, context));
        Files.writeString(dir.resolve("afon.properties"), "CLUSTER_NAME=" + clusterName + "\n");
        return new KubeClient(new FilePropertyProvider(dir.toString(), List.of("afon")), kubeConfig.toString());
    }

    @Test
    void refusesAClusterThatIsNotNamedAfterTheEnvButOnlyItsKindIsCheckedForAnotherProjects() throws Exception {
        try (KubeClient client = client(IDENTITY_CLUSTER, "lon-cluster")) {
            assertThatThrownBy(() -> client.requireContextOf(AfonCloud.AWS)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a cluster of this env");
            assertThatCode(() -> client.requireContextKindOf(AfonCloud.AWS)).doesNotThrowAnyException();
        }
    }

    @Test
    void stillRefusesAClusterOfAnotherKind() throws Exception {
        try (KubeClient client = client(IDENTITY_CLUSTER, "lon-cluster")) {
            assertThatThrownBy(() -> client.requireContextKindOf(AfonCloud.GCP)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a GKE cluster");
        }
    }

    @Test
    void acceptsTheClusterOfTheEnvOrOfItsStack() throws Exception {
        try (KubeClient client = client("arn:aws:eks:eu-west-2:111111111111:cluster/aws-full-lon-cluster", "lon-cluster")) {
            assertThatCode(() -> client.requireContextOf(AfonCloud.AWS)).doesNotThrowAnyException();
        }
    }
}

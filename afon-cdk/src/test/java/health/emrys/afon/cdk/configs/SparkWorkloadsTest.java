package health.emrys.afon.cdk.configs;

import health.emrys.afon.cdk.DockerImageResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SparkWorkloadsTest {

    @Test
    void sparkConnectServerRegistersNoCatalogsOrCredentials() {
        String command = new SparkWorkloads(new SparkNames("emrys"), DockerImageResolver.asDefined(), "https://oidc.example/realms/polaris").startConnectCommand();

        assertThat(command)
            .contains("org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .doesNotContain("spark.sql.catalog.")
            .doesNotContain(".credential")
            .doesNotContain(".token");
    }

    @Test
    void sparkConnectWaitsForTheMasterToBeReadyAndFailsIfItIsNot() {
        String command = new SparkWorkloads(new SparkNames("emrys"), DockerImageResolver.asDefined(), "https://oidc.example/realms/polaris").waitForMasterCommand();

        assertThat(command)
            .contains("kubectl get pod emrys-master-0")
            .contains("kubectl wait --for=condition=Ready pod/emrys-master-0 --timeout=600s")
            .contains("exit 1")
            // a shell expansion would be read by Terraform as an interpolation
            .doesNotContain("${");
    }

    @Test
    void sparkConnectLetsInOnlyClientsWithATokenOfTheRealmAndRefusesJvmCode() {
        String command = new SparkWorkloads(new SparkNames("emrys"), DockerImageResolver.asDefined(), "https://oidc.example/realms/polaris")
            .startConnectCommand();

        assertThat(command)
            .contains("\"spark.connect.grpc.interceptor.classes\":\"health.emrys.afon.spark.guard.SparkConnectGuardInterceptor\"")
            .contains("\"spark.sql.extensions\":\"org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions,"
                      + "health.emrys.afon.spark.guard.SparkGuardExtensions\"")
            .contains("\"spark.afon.connect.issuer\":\"https://oidc.example/realms/polaris\"")
            .contains("\"spark.afon.connect.keysUrl\":\"https://oidc.example/realms/polaris/protocol/openid-connect/certs\"");
    }

    @Test
    void theDriverHasNoTokenOfItsServiceAccountForUsersToRead() {
        String command = new SparkWorkloads(new SparkNames("emrys"), DockerImageResolver.asDefined(), "https://oidc.example/realms/polaris")
            .startConnectCommand();

        assertThat(command).contains("\"automountServiceAccountToken\":false");
    }

    @Test
    void theRealmMustBeAnHttpsUrl() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new SparkWorkloads(new SparkNames("emrys"), DockerImageResolver.asDefined(), "http://oidc.example/realms/polaris"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

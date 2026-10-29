package health.emrys.afon.cdk.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AzureCloudProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void tlsSecretNamesMatchIssueCertScript() {
        assertThat(AzureCloudProvider.tlsSecretNames("polaris.example.com"))
            .containsExactly("polaris-example-com-tls-crt", "polaris-example-com-tls-key");
    }

    @Test
    void restoreCommandReadsSecretsFromKeyVault() {
        String command = AzureCloudProvider.restoreCachedCertCommand("my-vault", new String[]{"a-tls-crt", "a-tls-key"}, "polaris-tls");
        assertThat(command).contains("--vault-name 'my-vault' --name 'a-tls-crt'")
                           .contains("--vault-name 'my-vault' --name 'a-tls-key'")
                           .contains("kubectl create secret generic polaris-tls")
                           .contains("kubectl apply --server-side")
                           .contains("kubectl annotate secret polaris-tls")
                           .contains("cert-manager.io/issuer-name=letsencrypt-prod")
                           // so that cert-manager has the secret in its cache when it first checks the certificate
                           .contains("kubectl label secret polaris-tls --overwrite controller.cert-manager.io/fao=true");
    }

    /**
     * The node pool may be separated by commas, which are safe where the properties file is sourced by a shell, or by the
     * semicolons that the property used before, which are not.
     */
    @Test
    void nodePoolPairsMayBeSeparatedByCommasOrSemicolons() {
        Map<String, String> expected = Map.of("vmSize", "Standard_D4s_v6", "nodeCount", "2");

        assertThat(AzureCloudProvider.parseNodePoolConfig("vmSize:Standard_D4s_v6,nodeCount:2")).isEqualTo(expected);
        assertThat(AzureCloudProvider.parseNodePoolConfig("vmSize:Standard_D4s_v6;nodeCount:2")).isEqualTo(expected);
        assertThat(AzureCloudProvider.parseNodePoolConfig(" vmSize : Standard_D4s_v6 , nodeCount : 2 ")).isEqualTo(expected);
        assertThat(AzureCloudProvider.parseNodePoolConfig("nodeCount:4")).containsExactly(Map.entry("nodeCount", "4"));
        assertThat(AzureCloudProvider.parseNodePoolConfig("")).isEmpty();
    }

    /**
     * The identity running tofu may be a user, a service principal or a managed identity, so the principal type of its
     * cluster admin role is left for Azure to resolve from the object id, rather than being fixed to a user.
     * Synthesised from the template config, so independent of the developer's own afon home.
     */
    @Test
    void theDeployerPrincipalTypeIsLeftForAzureToResolve(@TempDir Path dir) throws Exception {
        JsonNode plan = JSON.readTree(SynthesisSupport.synthesise(StackId.parse("azure-full"), dir, Map.of()));

        JsonNode admin = clusterAdmin(plan);
        assertThat(admin.isMissingNode()).isFalse();
        assertThat(admin.path("role_definition_name").asText()).isEqualTo("Azure Kubernetes Service RBAC Cluster Admin");
        assertThat(admin.path("principal_id").asText()).contains("data.azurerm_client_config.");
        assertThat(admin.has("principal_type")).isFalse();
    }

    private static JsonNode clusterAdmin(JsonNode plan) {
        for (var entry : plan.path("resource").path("azurerm_role_assignment").properties()) {
            if (entry.getKey().contains("tofu-cluster-admin")) {
                return entry.getValue();
            }
        }
        return JSON.missingNode();
    }

}

package health.emrys.afon.cdk.configs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GKE has no CiliumNetworkPolicy: the egress of the notebooks and of Spark, which on AWS and Azure it limits to the whitelisted domains,
 * is a NetworkPolicy for what is in the cluster and an FQDNNetworkPolicy for each domain.
 */
class NetworkConfigGkeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode plan(String stack, Path dir) throws Exception {
        // which the monitoring of gcp-full needs, for Loki to write its logs
        Map<String, String> config = Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\"}");
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir, config));
    }

    private static JsonNode block(JsonNode node) {
        return node.isArray() ? node.get(0) : node;
    }

    private static JsonNode policy(JsonNode plan, String name) {
        for (var entry : plan.path("resource").path("kubernetes_network_policy").properties()) {
            if (block(entry.getValue().path("metadata")).path("name").asText().equals(name)) {
                return entry.getValue();
            }
        }
        return JSON.missingNode();
    }

    @Test
    void oneFqdnPolicyForEachDomainSoThatNoneGoesPastTheCapOfAddressesOfAPolicy() {
        List<NetworkConfig.AllowedDomain> domains = List.of(
            NetworkConfig.AllowedDomain.create("storage.googleapis.com"),
            NetworkConfig.AllowedDomain.create("*.example.com"));

        Map<String, Object> manifest = NetworkConfig.fqdnPolicies("spark-driver", Map.of("spark-role", "driver"), domains);

        JsonNode list = JSON.valueToTree(manifest);
        assertThat(list.path("kind").asText()).isEqualTo("List");
        JsonNode items = list.path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("apiVersion").asText()).isEqualTo("networking.gke.io/v1alpha1");
        assertThat(items.get(0).path("kind").asText()).isEqualTo("FQDNNetworkPolicy");
        assertThat(items.get(0).path("metadata").path("name").asText()).isEqualTo("spark-driver-fqdn-0");
        assertThat(items.get(0).path("metadata").path("labels").path(NetworkConfig.FQDN_POLICY_LABEL).asText()).isEqualTo("spark-driver");
        assertThat(items.get(0).path("spec").path("podSelector").path("matchLabels").path("spark-role").asText()).isEqualTo("driver");
        // a name is matched as such, a wildcard as a pattern
        assertThat(items.get(0).path("spec").path("egress").get(0).path("matches").get(0).path("name").asText())
            .isEqualTo("storage.googleapis.com");
        assertThat(items.get(1).path("spec").path("egress").get(0).path("matches").get(0).path("pattern").asText())
            .isEqualTo("*.example.com");
    }

    @Test
    void gcpEnforcesNothingWithCilium(@TempDir Path dir) throws Exception {
        String plan = plan("gcp-full", dir).toString();

        assertThat(plan).doesNotContain("CiliumNetworkPolicy").doesNotContain("cilium.io/v2");
    }

    @Test
    void theSparkMasterAndDriverMayReachDnsAndTheMasterAndTheDriverTheIngressController(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("gcp-full", dir);

        JsonNode master = policy(plan, "allow-only-spark-master-egress");
        JsonNode driver = policy(plan, "allow-only-spark-driver-egress");
        assertThat(master.isMissingNode()).isFalse();
        assertThat(driver.isMissingNode()).isFalse();
        assertThat(block(master.path("spec")).path("policy_types").toString()).contains("Egress");
        assertThat(master.toString()).contains("kube-dns");
        assertThat(master.toString()).doesNotContain("ingress-nginx");
        assertThat(driver.toString()).contains("kube-dns").contains("ingress-nginx").contains("443");
    }

    @Test
    void dnsIsAllowedToTheNodeLocalCacheThatAnswersOnGkeAsWellAsToKubeDns(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("gcp-full", dir);

        for (String name : List.of("allow-only-specific-egress", "allow-only-spark-master-egress", "allow-only-spark-driver-egress")) {
            assertThat(policy(plan, name).toString()).as(name).contains("\"k8s-app\":\"kube-dns\"").contains("\"k8s-app\":\"node-local-dns\"");
        }
    }

    @Test
    void awsAndAzureDoNotAllowTheNodeLocalCache(@TempDir Path dir) throws Exception {
        assertThat(policy(plan("aws-full", dir), "allow-only-specific-egress").toString()).doesNotContain("node-local-dns");
    }

    @Test
    void theNotebooksMayReachTheSparkMasterWithTheirOwnNetworkPolicy(@TempDir Path dir) throws Exception {
        JsonNode notebooks = policy(plan("gcp-full", dir), "allow-only-specific-egress");

        assertThat(notebooks.toString()).contains("\"spark-role\":\"master\"");
    }

    @Test
    void awsStillUsesCilium(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("aws-full", dir);

        assertThat(plan.path("resource").path("null_resource").has("spark-driver-policy")).isTrue();
        assertThat(policy(plan, "allow-only-spark-driver-egress").isMissingNode()).isTrue();
    }
}

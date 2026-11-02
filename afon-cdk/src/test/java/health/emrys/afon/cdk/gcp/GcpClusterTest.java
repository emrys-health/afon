package health.emrys.afon.cdk.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GKE cluster enforces network policies, with Dataplane V2, and its nodes are those of {@code GCP_GKE_NODE_POOL}.
 */
class GcpClusterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode cluster(String stack, Path dir, Map<String, String> overrides) throws Exception {
        // which the monitoring of gcp-full needs, for Loki to write its logs
        Map<String, String> config = new HashMap<>(overrides);
        config.put("GOOGLE_APPLICATION_CREDENTIALS", "{\"type\":\"service_account\"}");
        JsonNode plan = JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir, config));
        return plan.path("resource").path("google_container_cluster").elements().next();
    }

    /** The synthesised plan has a single nested block as an object or as a one-element array, depending on the provider version. */
    private static String machineType(JsonNode cluster) {
        JsonNode config = cluster.path("node_config");
        return (config.isArray() ? config.get(0) : config).path("machine_type").asText();
    }

    @Test
    void theClusterUsesDataplaneV2SoThatNetworkPoliciesAreEnforced(@TempDir Path dir) throws Exception {
        assertThat(cluster("gcp-full", dir, Map.of()).path("datapath_provider").asText()).isEqualTo("ADVANCED_DATAPATH");
    }

    @Test
    void theClusterRegistersFqdnNetworkPoliciesAsGkeHasNoCiliumNetworkPolicy(@TempDir Path dir) throws Exception {
        assertThat(cluster("gcp-full", dir, Map.of()).path("enable_fqdn_network_policy").asBoolean()).isTrue();
    }

    @Test
    void thePodsAndServicesGetTheirAddressesFromTheSecondaryRangesOfTheSubnetSoThatThePolarisAllowListHoldsTheirRange(@TempDir Path dir)
        throws Exception {
        JsonNode policy = cluster("gcp-full", dir, Map.of()).path("ip_allocation_policy");
        policy = policy.isArray() ? policy.get(0) : policy;

        assertThat(policy.path("cluster_secondary_range_name").asText()).isEqualTo("pods");
        assertThat(policy.path("services_secondary_range_name").asText()).isEqualTo("services");
    }

    @Test
    void theClusterIsDestroyedBeforeTheNatSoThatThePreDeleteHooksOfItsReleasesCanStillPullImages(@TempDir Path dir) throws Exception {
        JsonNode dependsOn = cluster("gcp-full", dir, Map.of()).path("depends_on");

        assertThat(dependsOn.toString()).contains("google_compute_router_nat.");
    }

    @Test
    void theNodesAreOfTheDefaultSizeWhenNoNodePoolIsConfigured(@TempDir Path dir) throws Exception {
        JsonNode cluster = cluster("gcp-full", dir, Map.of());

        assertThat(cluster.path("initial_node_count").asInt()).isEqualTo(1);
        assertThat(machineType(cluster)).isEqualTo("e2-medium");
    }

    @Test
    void theNodesAreThoseOfTheConfiguredNodePool(@TempDir Path dir) throws Exception {
        JsonNode cluster = cluster("gcp-full", dir, Map.of("GCP_GKE_NODE_POOL", "machineType:e2-standard-4,nodeCount:2"));

        assertThat(cluster.path("initial_node_count").asInt()).isEqualTo(2);
        assertThat(machineType(cluster)).isEqualTo("e2-standard-4");
    }
}

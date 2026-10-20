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

/**
 * With {@code AZURE_EGRESS_IP} set, the outbound traffic of the cluster leaves from that public IP, which is created beforehand
 * and looked up by address, rather than from one that Azure allocates.
 */
class AzureEgressIpTest {

    private static final ObjectMapper JSON         = new ObjectMapper();
    private static final String       EGRESS_IP    = "51.142.64.202";
    private static final String       WHITELIST    = "nginx.ingress.kubernetes.io/whitelist-source-range";
    private static final Map<String, String> PINNED = Map.of("AZURE_EGRESS_IP", EGRESS_IP);

    private static JsonNode plan(String stack, Path dir, Map<String, String> overrides) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse(stack), dir, overrides));
    }

    private static JsonNode cluster(JsonNode plan) {
        return plan.path("resource").path("azurerm_kubernetes_cluster").elements().next();
    }

    private static JsonNode egressRoleAssignment(JsonNode plan) {
        JsonNode assignments = plan.path("resource").path("azurerm_role_assignment");
        for (var entry : assignments.properties()) {
            if (entry.getKey().contains("egress-ip")) {
                return entry.getValue();
            }
        }
        return JSON.missingNode();
    }

    @Test
    void theClusterIsLeftToAzureWhenNoEgressIpIsConfigured(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-full", dir, Map.of());

        assertThat(cluster(plan).path("network_profile").has("load_balancer_profile")).isFalse();
        assertThat(plan.path("data").has("azurerm_public_ips")).isFalse();
        assertThat(egressRoleAssignment(plan).isMissingNode()).isTrue();
        assertThat(plan.path("output").toString()).doesNotContain("egressIp");
    }

    @Test
    void theClusterEgressesFromTheConfiguredPublicIp(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-full", dir, PINNED);

        String outboundIp = cluster(plan).path("network_profile").path("load_balancer_profile")
                                         .path("outbound_ip_address_ids").get(0).asText();
        // found by its address among the public IPs of the resource group, which Azure cannot look up otherwise
        assertThat(outboundIp).contains("matchkeys(").contains("val.id").contains("val.ip_address")
                              .contains("\"" + EGRESS_IP + "\"")
                              .contains("data.azurerm_public_ips.");
        JsonNode lookup = plan.path("data").path("azurerm_public_ips").elements().next();
        assertThat(lookup.path("resource_group_name").asText()).contains("data.azurerm_resource_group.");
    }

    @Test
    void theClusterIdentityMayJoinThePublicIpAndTheClusterWaitsForIt(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-full", dir, PINNED);

        JsonNode assignment = egressRoleAssignment(plan);
        assertThat(assignment.path("role_definition_name").asText()).isEqualTo("Network Contributor");
        assertThat(assignment.path("principal_id").asText()).contains("azurerm_user_assigned_identity.");
        assertThat(assignment.path("scope").asText()).contains("matchkeys(").contains(EGRESS_IP);
        assertThat(cluster(plan).path("depends_on").toString()).contains("aks-identity-egress-ip-network-contributor");
    }

    @Test
    void theEgressIpIsReported(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-full", dir, PINNED);

        JsonNode output = plan.path("output").elements().next();
        assertThat(plan.path("output").fieldNames().next()).contains("egressIp");
        assertThat(output.path("value").asText()).isEqualTo(EGRESS_IP);
    }

    @Test
    void aStackThatDoesNotPinItsEgressIpIgnoresTheProperty(@TempDir Path dir) throws Exception {
        // azure-keycloak is defined with pinnedEgressIp false, so it can run beside the stack that holds the address
        JsonNode plan = plan("azure-keycloak", dir, PINNED);

        assertThat(cluster(plan).path("network_profile").has("load_balancer_profile")).isFalse();
        assertThat(plan.path("data").has("azurerm_public_ips")).isFalse();
    }

    @Test
    void polarisAdmitsTheEgressIpTogetherWithTheConfiguredCidrsAndThePods(@TempDir Path dir) throws Exception {
        JsonNode plan = plan("azure-full", dir, Map.of("AZURE_EGRESS_IP", EGRESS_IP, "AZURE_POLARIS_ALLOWED_CIDRS", "203.0.113.7/32"));

        assertThat(plan.path("resource").path("kubernetes_ingress_v1").path("polaris-ingress").path("metadata")
                       .path("annotations").path(WHITELIST).asText())
            .isEqualTo("203.0.113.7/32," + EGRESS_IP + "/32," + AzureCloudProvider.POD_CIDR);
    }

    @Test
    void theEgressIpAloneDoesNotRestrictPolaris(@TempDir Path dir) throws Exception {
        // as on AWS, it is added to the allow-list that is configured, never a restriction of its own
        JsonNode plan = plan("azure-full", dir, PINNED);

        assertThat(plan.path("resource").path("kubernetes_ingress_v1").path("polaris-ingress").path("metadata")
                       .path("annotations").has(WHITELIST)).isFalse();
    }
}

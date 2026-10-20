package health.emrys.afon.cdk.gcp;

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
 * With {@code GCP_EGRESS_IP} set, the outbound traffic of the cluster leaves its Cloud NAT from that static IP, which is created
 * beforehand and looked up by address, rather than from the addresses that Google allocates.
 */
class GcpEgressIpTest {

    private static final ObjectMapper JSON      = new ObjectMapper();
    private static final String       EGRESS_IP = "35.197.200.10";
    private static final Map<String, String> PINNED = Map.of("GCP_EGRESS_IP", EGRESS_IP);

    private static JsonNode plan(Path dir, Map<String, String> overrides) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse("gcp-polaris"), dir, overrides));
    }

    private static JsonNode nat(JsonNode plan) {
        return plan.path("resource").path("google_compute_router_nat").elements().next();
    }

    @Test
    void googleAllocatesTheAddressesWhenNoEgressIpIsConfigured(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, Map.of());

        assertThat(nat(plan).path("nat_ip_allocate_option").asText()).isEqualTo("AUTO_ONLY");
        assertThat(nat(plan).has("nat_ips")).isFalse();
        assertThat(plan.path("data").has("google_compute_addresses")).isFalse();
        assertThat(plan.path("output").toString()).doesNotContain("egressIp");
    }

    @Test
    void theCloudNatUsesTheConfiguredStaticIp(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, PINNED);

        assertThat(nat(plan).path("nat_ip_allocate_option").asText()).isEqualTo("MANUAL_ONLY");
        assertThat(nat(plan).path("nat_ips").get(0).asText())
            .contains("data.google_compute_addresses.").contains("addresses[0].self_link");
    }

    @Test
    void theStaticIpIsFoundByItsAddressInTheProjectAndRegion(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, PINNED);

        JsonNode lookup = plan.path("data").path("google_compute_addresses").elements().next();
        assertThat(lookup.path("filter").asText()).isEqualTo("address = \"" + EGRESS_IP + "\"");
        // that of the stack, not of whoever runs it
        assertThat(lookup.path("project").asText()).isNotBlank();
        assertThat(lookup.path("region").asText()).isEqualTo("europe-west2");
        assertThat(nat(plan).path("region").asText()).isEqualTo(lookup.path("region").asText());
    }

    @Test
    void theEgressIpIsReported(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, PINNED);

        assertThat(plan.path("output").fieldNames().next()).contains("egressIp");
        assertThat(plan.path("output").elements().next().path("value").asText()).isEqualTo(EGRESS_IP);
    }
}

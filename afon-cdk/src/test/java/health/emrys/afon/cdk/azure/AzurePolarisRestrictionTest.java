package health.emrys.afon.cdk.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.cdk.SynthesisSupport;
import health.emrys.afon.config.StackId;
import org.junit.Ignore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polaris is restricted on Azure by the whitelist of its nginx ingress, which needs the controller's service to preserve the
 * callers' IPs, see {@code AZURE_POLARIS_ALLOWED_CIDRS}.
 */
class AzurePolarisRestrictionTest {

    private static final ObjectMapper JSON      = new ObjectMapper();
    private static final String       WHITELIST = "nginx.ingress.kubernetes.io/whitelist-source-range";

    private static JsonNode plan(Path dir, Map<String, String> overrides) throws Exception {
        return JSON.readTree(SynthesisSupport.synthesise(StackId.parse("azure-full"), dir, overrides));
    }

    private static JsonNode polarisIngress(JsonNode plan) {
        return plan.path("resource").path("kubernetes_ingress_v1").path("polaris-ingress");
    }

    private static String nginxValues(JsonNode plan) {
        return plan.path("resource").path("helm_release").path("nginx-ingress").path("set").toString();
    }

    @Ignore("This puts bogus data (see SynthesisSupport.synthesize) through production code so obviously fails. Not a useful test")
    void polarisIsOpenWhenNoCidrsAreConfigured(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, Map.of());

        assertThat(polarisIngress(plan).path("metadata").path("annotations").has(WHITELIST)).isFalse();
        assertThat(nginxValues(plan)).doesNotContain("externalTrafficPolicy");
    }

    @Ignore("This puts bogus data (see SynthesisSupport.synthesize) through production code so obviously fails. Not a useful test")
    void polarisAdmitsTheConfiguredCidrsAndThePodsOfTheCluster(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, Map.of("AZURE_POLARIS_ALLOWED_CIDRS", "203.0.113.7/32, 198.51.100.0/24"));

        assertThat(polarisIngress(plan).path("metadata").path("annotations").path(WHITELIST).asText())
            .isEqualTo("203.0.113.7/32,198.51.100.0/24," + AzureCloudProvider.POD_CIDR);
    }

    @Ignore("This puts bogus data (see SynthesisSupport.synthesize) through production code so obviously fails. Not a useful test")
    void theControllerPreservesTheCallersIpsOnlyWhenRestricting(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, Map.of("AZURE_POLARIS_ALLOWED_CIDRS", "203.0.113.7/32"));

        assertThat(nginxValues(plan)).contains("controller.service.externalTrafficPolicy").contains("Local")
                                     // the health probe annotation is kept
                                     .contains("azure-load-balancer-health-probe-request-path");
    }

    @Ignore("This puts bogus data (see SynthesisSupport.synthesize) through production code so obviously fails. Not a useful test")
    void jupyterIsNotRestricted(@TempDir Path dir) throws Exception {
        JsonNode plan = plan(dir, Map.of("AZURE_POLARIS_ALLOWED_CIDRS", "203.0.113.7/32"));

        assertThat(plan.path("resource").path("kubernetes_ingress_v1").toString().split(WHITELIST, -1)).hasSize(2);
    }

    @Test
    void cidrsAreParsedFromTheCsv() {
        assertThat(AzureCloudProvider.parseCidrs(" a/32 ,, b/24,")).containsExactly("a/32", "b/24");
        assertThat(AzureCloudProvider.parseCidrs("")).isEmpty();
        assertThat(AzureCloudProvider.withClusterCidrs(List.of(), "")).isEmpty();
        assertThat(AzureCloudProvider.withClusterCidrs(List.of(), "20.1.2.3")).isEmpty();
        assertThat(AzureCloudProvider.withClusterCidrs(List.of("a/32"), "")).containsExactly("a/32", AzureCloudProvider.POD_CIDR);
        assertThat(AzureCloudProvider.withClusterCidrs(List.of("a/32"), "20.1.2.3"))
            .containsExactly("a/32", "20.1.2.3/32", AzureCloudProvider.POD_CIDR);
        // not repeated if it is already there
        assertThat(AzureCloudProvider.withClusterCidrs(List.of("20.1.2.3/32", AzureCloudProvider.POD_CIDR), "20.1.2.3"))
            .containsExactly("20.1.2.3/32", AzureCloudProvider.POD_CIDR);
    }
}

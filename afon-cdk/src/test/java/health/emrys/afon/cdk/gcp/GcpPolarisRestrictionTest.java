package health.emrys.afon.cdk.gcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GcpPolarisRestrictionTest {

    @Test
    void nothingIsRestrictedWhenNothingIsConfigured() {
        assertThat(GcpCloudProvider.withClusterCidrs(List.of(), "")).isEmpty();
        assertThat(GcpCloudProvider.withClusterCidrs(List.of(), "34.1.2.3")).isEmpty();
    }

    @Test
    void theClusterOwnAddressesAreAdded() {
        assertThat(GcpCloudProvider.withClusterCidrs(List.of("a/32"), ""))
            .containsExactly("a/32", GcpCloudProvider.POD_CIDR, GcpCloudProvider.NODE_CIDR);
        assertThat(GcpCloudProvider.withClusterCidrs(List.of("a/32"), "34.1.2.3"))
            .containsExactly("a/32", "34.1.2.3/32", GcpCloudProvider.POD_CIDR, GcpCloudProvider.NODE_CIDR);
    }

    @Test
    void theClusterOwnAddressesAreNotRepeated() {
        assertThat(GcpCloudProvider.withClusterCidrs(
            List.of("34.1.2.3/32", GcpCloudProvider.POD_CIDR, GcpCloudProvider.NODE_CIDR), "34.1.2.3"))
            .containsExactly("34.1.2.3/32", GcpCloudProvider.POD_CIDR, GcpCloudProvider.NODE_CIDR);
    }
}

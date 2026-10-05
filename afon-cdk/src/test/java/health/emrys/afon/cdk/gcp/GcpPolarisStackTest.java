package health.emrys.afon.cdk.gcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GcpPolarisStackTest {

    @Test
    void dnsZoneNameIsDomainNameWithDotsAsDashes() {
        assertThat(GcpPolarisStack.dnsZoneName("emryspolarisgcp2.click")).isEqualTo("emryspolarisgcp2-click");
        assertThat(GcpPolarisStack.dnsZoneName("polaris.emrys.example.com")).isEqualTo("polaris-emrys-example-com");
    }

    @Test
    void dnsZoneNameMustBeValid() {
        assertThatThrownBy(() -> GcpPolarisStack.dnsZoneName("Polaris.click")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcpPolarisStack.dnsZoneName("1polaris.click")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcpPolarisStack.dnsZoneName("polaris.click.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcpPolarisStack.dnsZoneName("a".repeat(60) + ".click"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("63");
    }

}

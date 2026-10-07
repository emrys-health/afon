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

    @Test
    void tlsSecretNamesMatchSecretsScript() {
        assertThat(GcpPolarisStack.tlsSecretNames("polaris.example.com"))
            .containsExactly("polaris-example-com-tls-crt", "polaris-example-com-tls-key");
    }

    @Test
    void restoreCommandReadsSecretsFromProject() {
        String command = GcpPolarisStack.restoreCachedCertCommand("my-proj", new String[]{"a-tls-crt", "a-tls-key"});
        assertThat(command).contains("--secret='a-tls-crt' --project='my-proj'")
                           .contains("--secret='a-tls-key' --project='my-proj'")
                           .contains("kubectl create secret generic polaris-tls")
                           .contains("kubectl apply --server-side")
                           .contains("kubectl annotate secret polaris-tls")
                           .contains("cert-manager.io/issuer-name=letsencrypt-prod");
    }

}

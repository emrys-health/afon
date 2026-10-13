package health.emrys.afon.cdk.gcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GcpCloudProviderTest {

    @Test
    void dnsZoneNameIsDomainNameWithDotsAsDashes() {
        assertThat(GcpCloudProvider.dnsZoneName("emryspolarisgcp2.click")).isEqualTo("emryspolarisgcp2-click");
        assertThat(GcpCloudProvider.dnsZoneName("polaris.emrys.example.com")).isEqualTo("polaris-emrys-example-com");
    }

    @Test
    void dnsZoneNameMustBeValid() {
        assertThatThrownBy(() -> GcpCloudProvider.dnsZoneName("Polaris.click")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcpCloudProvider.dnsZoneName("1polaris.click")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcpCloudProvider.dnsZoneName("polaris.click.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GcpCloudProvider.dnsZoneName("a".repeat(60) + ".click"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("63");
    }

    @Test
    void tlsSecretNamesMatchSecretsScript() {
        assertThat(GcpCloudProvider.tlsSecretNames("polaris.example.com"))
            .containsExactly("polaris-example-com-tls-crt", "polaris-example-com-tls-key");
    }

    @Test
    void restoreCommandReadsSecretsFromProject() {
        String command = GcpCloudProvider.restoreCachedCertCommand("my-proj", new String[]{"a-tls-crt", "a-tls-key"}, "polaris-tls");
        assertThat(command).contains("--secret='a-tls-crt' --project='my-proj'")
                           .contains("--secret='a-tls-key' --project='my-proj'")
                           .contains("kubectl create secret generic polaris-tls")
                           .contains("kubectl apply --server-side")
                           .contains("kubectl annotate secret polaris-tls")
                           .contains("cert-manager.io/issuer-name=letsencrypt-prod")
                           // so that cert-manager has the secret in its cache when it first checks the certificate
                           .contains("kubectl label secret polaris-tls --overwrite controller.cert-manager.io/fao=true");
    }

}

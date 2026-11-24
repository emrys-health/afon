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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GcpCloudProviderTest {

    @Test
    void dnsZoneNameIsDomainNameWithDotsAsDashes() {
        assertThat(GcpCloudProvider.dnsZoneName("emryspolarisgcp2.click")).isEqualTo("emryspolarisgcp2-click");
        assertThat(GcpCloudProvider.dnsZoneName("polaris.emrys.example.com")).isEqualTo("polaris-emrys-example-com");
    }

    @Test
    void clusterNameIsQualifiedByTheStackType() {
        assertThat(GcpCloudProvider.clusterName("emrys", StackId.parse("gcp-polaris"))).isEqualTo("polaris-emrys");
        assertThat(GcpCloudProvider.clusterName("emrys", StackId.parse("gcp-keycloak"))).isEqualTo("keycloak-emrys");
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

    @Test
    void authRouteUsesJupyterZoneAndOneHourIdleTimeoutAnnotations(@TempDir Path dir) throws Exception {
        String planText = SynthesisSupport.synthesise(
            StackId.parse("gcp-full"), dir, Map.of("GOOGLE_APPLICATION_CREDENTIALS", "{\"a\":\"b\"}"));
        JsonNode plan = new ObjectMapper().readTree(planText);

        JsonNode authZone = plan.path("data").path("google_dns_managed_zone").path("dns-zone-jupyter-auth");
        assertThat(authZone.path("name").asText()).isEqualTo("jupyter-gcp-golden-example");

        boolean sawAuthRecord = false;
        for (var entry : plan.path("resource").path("google_dns_record_set").properties()) {
            JsonNode record = entry.getValue();
            if ("auth.jupyter-gcp.golden.example.".equals(record.path("name").asText())) {
                sawAuthRecord = true;
                assertThat(record.path("managed_zone").asText()).contains("dns-zone-jupyter-auth");
            }
        }
        assertThat(sawAuthRecord).isTrue();

        int matchedIngresses = 0;
        for (var entry : plan.path("resource").path("kubernetes_ingress_v1").properties()) {
            String name = entry.getValue().path("metadata").path("name").asText();
            if ("jupyter-ingress".equals(name) || "jupyter-auth-ingress".equals(name)) {
                matchedIngresses++;
                JsonNode annotations = entry.getValue().path("metadata").path("annotations");
                assertThat(annotations.path("nginx.ingress.kubernetes.io/proxy-read-timeout").asText()).isEqualTo("3600");
                assertThat(annotations.path("nginx.ingress.kubernetes.io/proxy-send-timeout").asText()).isEqualTo("3600");
            }
        }
        assertThat(matchedIngresses).isEqualTo(2);
    }

}

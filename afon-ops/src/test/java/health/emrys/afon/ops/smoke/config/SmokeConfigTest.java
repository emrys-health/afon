package health.emrys.afon.ops.smoke.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.cdk.configs.WhitelistConfig;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperty.AWS_EGRESS_IP;
import static health.emrys.afon.config.AfonProperty.AWS_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AWS_OIDC_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_OIDC_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.OIDC_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.TRE_CLOUD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class SmokeConfigTest {

    private static final String WHITELIST = """
        common:
          domains:
            - ${OIDC_DOMAIN_NAME}
        aws:
          domains:
            - ${AWS_OIDC_DOMAIN_NAME}
            - ${AWS_POLARIS_DOMAIN_NAME}
            - ${AWS_JUPYTER_DOMAIN_NAME}
        azure:
          domains:
            - ${AZURE_OIDC_DOMAIN_NAME}
            - ${AZURE_POLARIS_DOMAIN_NAME}
            - ${AZURE_JUPYTER_DOMAIN_NAME}
            - store.blob.core.windows.net
        gcp:
          domains:
            - ${GCP_OIDC_DOMAIN_NAME}
            - ${GCP_POLARIS_DOMAIN_NAME}
            - ${GCP_JUPYTER_DOMAIN_NAME}
        """;

    @TempDir
    Path configDir;

    private SmokeConfig config(String treCloud) throws IOException {
        return config(treCloud, Map.of());
    }

    private SmokeConfig config(String treCloud, Map<AfonProperty, String> extra) throws IOException {
        Map<AfonProperty, String> values = new HashMap<>(Map.of(
            OIDC_DOMAIN_NAME, "oidc.example",
            AWS_POLARIS_DOMAIN_NAME, "pol-aws.example", AWS_JUPYTER_DOMAIN_NAME, "jup-aws.example",
            AZURE_POLARIS_DOMAIN_NAME, "pol-az.example", AZURE_JUPYTER_DOMAIN_NAME, "jup-az.example",
            GCP_POLARIS_DOMAIN_NAME, "pol-gcp.example", GCP_JUPYTER_DOMAIN_NAME, "jup-gcp.example",
            AWS_EGRESS_IP, "203.0.113.9"));
        values.putAll(extra);
        if (treCloud != null) {
            values.put(TRE_CLOUD, treCloud);
        }
        Files.writeString(configDir.resolve(WhitelistConfig.WHITELIST_FILE), WHITELIST);
        Properties raw = new Properties();
        values.forEach((property, value) -> raw.setProperty(property.getName(), value));
        AfonPropertyProvider properties = mock(AfonPropertyProvider.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        org.mockito.Mockito.doAnswer(call -> values.getOrDefault(call.<AfonProperty>getArgument(0), call.getArgument(1)))
                           .when(properties).getValue(any(AfonProperty.class), anyString());
        org.mockito.Mockito.doReturn(raw).when(properties).getProperties();
        return new SmokeConfig(properties, new WhitelistConfig(properties, configDir.toString()));
    }

    @Test
    void cloudDefaultsToAws() throws IOException {
        assertThat(config(null).cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(config("  ").cloud()).isEqualTo(AfonCloud.AWS);
    }

    @Test
    void cloudIsThatOfTreCloud() throws IOException {
        assertThat(config("azure").cloud()).isEqualTo(AfonCloud.AZURE);
        assertThat(config("GCP").cloud()).isEqualTo(AfonCloud.GCP);
    }

    @Test
    void invalidCloudFails() throws IOException {
        assertThatThrownBy(() -> config("mars").cloud()).isInstanceOf(IllegalArgumentException.class)
                                                         .hasMessageContaining("mars");
    }

    @Test
    void onAwsAllowedDomainsAreThoseOfAwsOnly() throws IOException {
        assertThat(config("aws").allowedDomains()).containsExactly("oidc.example", "pol-aws.example", "jup-aws.example");
    }

    @Test
    void onAzureAllowedDomainsAreThoseOfAzureOnly() throws IOException {
        assertThat(config("azure").allowedDomains())
            .containsExactly("oidc.example", "pol-az.example", "jup-az.example", "store.blob.core.windows.net");
    }

    @Test
    void onGcpAllowedDomainsAreThoseOfGcpOnly() throws IOException {
        assertThat(config("gcp").allowedDomains()).containsExactly("oidc.example", "pol-gcp.example", "jup-gcp.example");
    }

    @Test
    void ownOidcDomainNameIsAllowedBesideTheGlobalOne() throws IOException {
        SmokeConfig config = config("aws", Map.of(AWS_OIDC_DOMAIN_NAME, "oidc-aws.example",
                                                  AZURE_OIDC_DOMAIN_NAME, "oidc-az.example"));
        assertThat(config.allowedDomains()).containsExactly("oidc.example", "oidc-aws.example", "pol-aws.example", "jup-aws.example");
    }

    @Test
    void awsAdviceNamesTheAlbAnnotationAndTheEgressIp() throws IOException {
        assertThat(config("aws").polarisRestrictionAdvice())
            .contains("alb.ingress.kubernetes.io/inbound-cidrs=203.0.113.9/32").contains("afon-aws.properties");
    }

    @Test
    void azureAdviceNamesTheIngressWhitelistAndNotAws() throws IOException {
        assertThat(config("azure").polarisRestrictionAdvice())
            .contains("AZURE_POLARIS_ALLOWED_CIDRS").contains("nginx.ingress.kubernetes.io/whitelist-source-range")
            .doesNotContain("alb.ingress").doesNotContain("afon-aws.properties");
    }

    @Test
    void gcpAdviceNamesTheIngressWhitelistAndNotAws() throws IOException {
        assertThat(config("gcp").polarisRestrictionAdvice())
            .contains("GCP_POLARIS_ALLOWED_CIDRS").contains("nginx.ingress.kubernetes.io/whitelist-source-range")
            .doesNotContain("alb.ingress").doesNotContain("afon-aws.properties");
    }

    @Test
    void onlyTheIngressControllersOwn403IsARejection() throws IOException {
        String nginx = "<html><body><center><h1>403 Forbidden</h1></center><hr><center>nginx</center></body></html>";
        SmokeConfig config = config("azure");
        assertThat(config.rejectedByIngress(403, nginx)).isTrue();
        assertThat(config.rejectedByIngress(403, "{\"error\":\"forbidden\"}")).isFalse();
        assertThat(config.rejectedByIngress(401, nginx)).isFalse();
        assertThat(config.rejectedByIngress(403, null)).isFalse();
    }
}

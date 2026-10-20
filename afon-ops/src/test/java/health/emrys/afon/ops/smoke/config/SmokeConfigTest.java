package health.emrys.afon.ops.smoke.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.ALLOWED_DOMAINS_CSV;
import static health.emrys.afon.config.AfonProperty.AWS_EGRESS_IP;
import static health.emrys.afon.config.AfonProperty.AWS_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AWS_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.AZURE_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_JUPYTER_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.GCP_POLARIS_DOMAIN_NAME;
import static health.emrys.afon.config.AfonProperty.TRE_CLOUD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class SmokeConfigTest {

    private static final String ALLOWED = "oidc.example,pol-aws.example,jup-aws.example,POL-AZ.example,jup-az.example,"
                                          + "pol-gcp.example,jup-gcp.example,store.blob.core.windows.net";

    private static SmokeConfig config(String treCloud) {
        Map<AfonProperty, String> values = new HashMap<>(Map.of(
            ALLOWED_DOMAINS_CSV, ALLOWED,
            AWS_POLARIS_DOMAIN_NAME, "pol-aws.example", AWS_JUPYTER_DOMAIN_NAME, "jup-aws.example",
            AZURE_POLARIS_DOMAIN_NAME, "pol-az.example", AZURE_JUPYTER_DOMAIN_NAME, "jup-az.example",
            GCP_POLARIS_DOMAIN_NAME, "pol-gcp.example", GCP_JUPYTER_DOMAIN_NAME, "jup-gcp.example",
            AWS_EGRESS_IP, "203.0.113.9"));
        if (treCloud != null) {
            values.put(TRE_CLOUD, treCloud);
        }
        AfonPropertyProvider properties = mock(AfonPropertyProvider.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        org.mockito.Mockito.doAnswer(call -> values.getOrDefault(call.<AfonProperty>getArgument(0), call.getArgument(1)))
                           .when(properties).getValue(any(AfonProperty.class), anyString());
        return new SmokeConfig(properties);
    }

    @Test
    void cloudDefaultsToAws() {
        assertThat(config(null).cloud()).isEqualTo(AfonCloud.AWS);
        assertThat(config("  ").cloud()).isEqualTo(AfonCloud.AWS);
    }

    @Test
    void cloudIsThatOfTreCloud() {
        assertThat(config("azure").cloud()).isEqualTo(AfonCloud.AZURE);
        assertThat(config("GCP").cloud()).isEqualTo(AfonCloud.GCP);
    }

    @Test
    void invalidCloudFails() {
        assertThatThrownBy(() -> config("mars").cloud()).isInstanceOf(IllegalArgumentException.class)
                                                         .hasMessageContaining("mars");
    }

    @Test
    void onAwsAllowedDomainsExcludeThoseOfTheOtherClouds() {
        assertThat(config("aws").allowedDomains())
            .containsExactly("oidc.example", "pol-aws.example", "jup-aws.example", "store.blob.core.windows.net");
    }

    @Test
    void onAzureAllowedDomainsExcludeThoseOfTheOtherClouds() {
        assertThat(config("azure").allowedDomains())
            .containsExactly("oidc.example", "POL-AZ.example", "jup-az.example", "store.blob.core.windows.net");
    }

    @Test
    void onGcpAllowedDomainsExcludeThoseOfTheOtherClouds() {
        assertThat(config("gcp").allowedDomains())
            .containsExactly("oidc.example", "pol-gcp.example", "jup-gcp.example", "store.blob.core.windows.net");
    }

    @Test
    void awsAdviceNamesTheAlbAnnotationAndTheEgressIp() {
        assertThat(config("aws").polarisRestrictionAdvice())
            .contains("alb.ingress.kubernetes.io/inbound-cidrs=203.0.113.9/32").contains("afon-aws.properties");
    }

    @Test
    void azureAdviceNamesTheIngressWhitelistAndNotAws() {
        assertThat(config("azure").polarisRestrictionAdvice())
            .contains("AZURE_POLARIS_ALLOWED_CIDRS").contains("nginx.ingress.kubernetes.io/whitelist-source-range")
            .doesNotContain("alb.ingress").doesNotContain("afon-aws.properties");
    }

    @Test
    void gcpAdviceNamesTheIngressWhitelistAndNotAws() {
        assertThat(config("gcp").polarisRestrictionAdvice())
            .contains("GCP_POLARIS_ALLOWED_CIDRS").contains("nginx.ingress.kubernetes.io/whitelist-source-range")
            .doesNotContain("alb.ingress").doesNotContain("afon-aws.properties");
    }

    @Test
    void onlyTheIngressControllersOwn403IsARejection() {
        String nginx = "<html><body><center><h1>403 Forbidden</h1></center><hr><center>nginx</center></body></html>";
        SmokeConfig config = config("azure");
        assertThat(config.rejectedByIngress(403, nginx)).isTrue();
        assertThat(config.rejectedByIngress(403, "{\"error\":\"forbidden\"}")).isFalse();
        assertThat(config.rejectedByIngress(401, nginx)).isFalse();
        assertThat(config.rejectedByIngress(403, null)).isFalse();
    }
}

package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudPropertyTest {

    static class Constants {
        public static final String AWS_POLARIS_DOMAIN_NAME  = "polaris.aws.example";
        public static final String AZURE_POLARIS_DOMAIN_NAME = "polaris.azure.example";
    }

    private final AfonPropertyProvider properties = new ConstantsPropertyProvider(Constants.class);

    @Test
    void resolvesPropertyOfCloudByConvention() {
        assertThat(AfonProperty.forCloud(AWS, "POLARIS_DOMAIN_NAME")).contains(AfonProperty.AWS_POLARIS_DOMAIN_NAME);
        assertThat(AfonProperty.forCloud(AZURE, "POLARIS_DOMAIN_NAME")).contains(AfonProperty.AZURE_POLARIS_DOMAIN_NAME);
        assertThat(AfonProperty.forCloud(GCP, "POLARIS_DOMAIN_NAME")).contains(AfonProperty.GCP_POLARIS_DOMAIN_NAME);
    }

    @Test
    void hasNoPropertyOfCloudWhenNotDeclared() {
        // only AWS has DNS zones
        assertThat(AfonProperty.forCloud(AZURE, "POLARIS_DNS_ZONE")).isEmpty();
        assertThat(AfonProperty.forCloud(AWS, "UNKNOWN")).isEmpty();
    }

    @Test
    void readsValueOfCloud() {
        assertThat(properties.getCloudValue(AWS, "POLARIS_DOMAIN_NAME")).isEqualTo("polaris.aws.example");
        assertThat(properties.getCloudValue(AZURE, "POLARIS_DOMAIN_NAME")).isEqualTo("polaris.azure.example");
    }

    @Test
    void cannotReadValueOfCloudNotDeclaredOrNotSet() {
        assertThatThrownBy(() -> properties.getCloudValue(AZURE, "POLARIS_DNS_ZONE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No property AZURE_POLARIS_DNS_ZONE for the cloud azure");
        assertThatThrownBy(() -> properties.getCloudValue(GCP, "POLARIS_DOMAIN_NAME"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsValueOfCloudNotDeclaredOrNotSet() {
        assertThat(properties.getCloudValue(AWS, "POLARIS_DOMAIN_NAME", "default")).isEqualTo("polaris.aws.example");
        assertThat(properties.getCloudValue(AZURE, "POLARIS_DNS_ZONE", "default")).isEqualTo("default");
        assertThat(properties.getCloudValue(GCP, "POLARIS_DOMAIN_NAME", "default")).isEqualTo("default");
    }
}

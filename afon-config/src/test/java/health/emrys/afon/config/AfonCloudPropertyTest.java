package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static health.emrys.afon.config.AfonCloud.AWS;
import static health.emrys.afon.config.AfonCloud.AZURE;
import static health.emrys.afon.config.AfonCloud.GCP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfonCloudPropertyTest {

    static class Constants {
        public static final String AWS_POLARIS_DOMAIN_NAME  = "polaris.aws.example";
        public static final String AZURE_POLARIS_DOMAIN_NAME = "polaris.azure.example";
    }

    private final AfonPropertyProvider properties = new ConstantsPropertyProvider(Constants.class);

    @Test
    void resolvesPropertyOfCloudByConvention() {
        assertThat(AfonCloudProperty.POLARIS_DOMAIN_NAME.forCloud(AWS)).contains(AfonProperty.AWS_POLARIS_DOMAIN_NAME);
        assertThat(AfonCloudProperty.POLARIS_DOMAIN_NAME.forCloud(AZURE)).contains(AfonProperty.AZURE_POLARIS_DOMAIN_NAME);
        assertThat(AfonCloudProperty.POLARIS_DOMAIN_NAME.forCloud(GCP)).contains(AfonProperty.GCP_POLARIS_DOMAIN_NAME);
    }

    @Test
    void hasNoPropertyOfCloudWhenNotDeclared() {
        // GCP has no log bucket
        assertThat(AfonCloudProperty.POLARIS_LOG_BUCKET.forCloud(GCP)).isEmpty();
    }

    @Test
    void readsValueOfCloud() {
        assertThat(properties.getCloudValue(AWS, AfonCloudProperty.POLARIS_DOMAIN_NAME)).isEqualTo("polaris.aws.example");
        assertThat(properties.getCloudValue(AZURE, AfonCloudProperty.POLARIS_DOMAIN_NAME)).isEqualTo("polaris.azure.example");
    }

    @Test
    void cannotReadValueOfCloudNotDeclaredOrNotSet() {
        assertThatThrownBy(() -> properties.getCloudValue(GCP, AfonCloudProperty.POLARIS_LOG_BUCKET))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No property GCP_POLARIS_LOG_BUCKET for the cloud gcp");
        assertThatThrownBy(() -> properties.getCloudValue(GCP, AfonCloudProperty.POLARIS_DOMAIN_NAME))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsValueOfCloudNotDeclaredOrNotSet() {
        assertThat(properties.getCloudValue(AWS, AfonCloudProperty.POLARIS_DOMAIN_NAME, "default")).isEqualTo("polaris.aws.example");
        assertThat(properties.getCloudValue(GCP, AfonCloudProperty.POLARIS_LOG_BUCKET, "default")).isEqualTo("default");
        assertThat(properties.getCloudValue(GCP, AfonCloudProperty.POLARIS_DOMAIN_NAME, "default")).isEqualTo("default");
    }

    @Test
    void everyCloudPropertyIsDeclaredByAtLeastOneCloud() {
        // guards against drift: renaming an AfonCloudProperty without the AfonProperty of its clouds (or vice versa) leaves it resolving to none
        for (AfonCloudProperty cloudProperty : AfonCloudProperty.values()) {
            assertThat(Arrays.stream(AfonCloud.values()).map(cloudProperty::forCloud).flatMap(java.util.Optional::stream))
                .as("AfonProperty declared for the cloud-agnostic name %s", cloudProperty)
                .isNotEmpty();
        }
    }
}

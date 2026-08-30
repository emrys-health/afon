package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class ConstantsPropertyProviderTest {

    static class Constants1 {
        public static final String CLUSTER_NAME = "dev";
        public static final String AWS_REGION = "aws-region";
        private static final String HIDDEN = "not visible"; // not public, must be ignored
    }

    static class Constants2 {
        public static final String GCP_BUCKET_NAME = "dev-bucket";
        public static final String NON_AFON_PROPERTY = "any";
        public String instanceField = "not static"; // not static, must be ignored
    }

    @Test
    public void canGetPropertiesFromConstantsOfMultipleClasses() {
        AfonPropertyProvider provider = new ConstantsPropertyProvider(Constants1.class, Constants2.class);
        Properties properties = provider.getProperties();
        assertThat(properties.getProperty("CLUSTER_NAME")).isEqualTo(Constants1.CLUSTER_NAME.toString());
        assertThat(properties.getProperty("AWS_REGION")).isEqualTo(Constants1.AWS_REGION.toString());
        assertThat(properties.getProperty("GCP_BUCKET_NAME")).isEqualTo(Constants2.GCP_BUCKET_NAME.toString());
        assertThat(properties.getProperty("HIDDEN")).isNull();
        assertThat(properties.getProperty("instanceField")).isNull();
        assertThat(properties.getProperty("NON_AFON_PROPERTY")).isNull();
    }

    @Test
    public void canGetPropertyByKey() {
        AfonPropertyProvider provider = new ConstantsPropertyProvider(Constants1.class);
        assertThat(provider.hasProperty(AfonProperty.CLUSTER_NAME)).isTrue();
        assertThat(provider.getValue(AfonProperty.CLUSTER_NAME)).isEqualTo(Constants1.CLUSTER_NAME.toString());
    }

    @Test
    public void missingPropertyIsAbsent() {
        AfonPropertyProvider provider = new ConstantsPropertyProvider(Constants1.class);
        assertThat(provider.hasProperty(AfonProperty.AZURE_REGION)).isFalse();
        assertThat(provider.getValue(AfonProperty.AZURE_REGION, "default")).isEqualTo("default");
    }

    @Test
    public void hasNoSecretProperties() {
        AfonPropertyProvider provider = new ConstantsPropertyProvider(Constants1.class);
        assertThat(provider.getSecretProperties()).isEmpty();
    }

    @Test
    public void canSetPropertyValueAfterLoad() {
        AfonPropertyProvider provider = new ConstantsPropertyProvider(Constants1.class);
        provider.setValue(AfonProperty.CLUSTER_NAME, "prod");
        assertThat(provider.getValue(AfonProperty.CLUSTER_NAME)).isEqualTo("prod");
    }

}

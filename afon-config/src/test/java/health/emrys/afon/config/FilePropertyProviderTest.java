package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FilePropertyProviderTest {

    static String CONFIG_DIR = "src/test/resources/config";

    @Test
    public void cannotGetPropertiesWhenFilesAreMissing() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR, List.of("missing"));
        try {
            provider.getProperties();
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).endsWith("missing.properties (No such file or directory)");
        }
    }

    @Test
    public void canRetrieveAllPropertyNames() {
        assertThat(AfonProperty.getAllNames()).hasSizeGreaterThan(0);
    }

    @Test
    public void canOverridePropertyValueAfterLoad() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR);
        AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
        assertThat(provider.hasProperty(polarisEndpoint)).isTrue();
        assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8181"); // default value
        provider.setValue(polarisEndpoint, "localhost:8182");
        assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8182"); // overridden value
    }


    @Test
    public void canOverridePropertyValueBySystemPropertyUponLoad() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR);
        try {
            System.setProperty(AfonProperty.POLARIS_ENDPOINT.getName(), "localhost:8182"); // overridden value
            AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
            assertThat(provider.hasProperty(polarisEndpoint)).isTrue();
            assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8182");
        } finally {
            System.clearProperty(AfonProperty.POLARIS_ENDPOINT.getName());
        }
    }

    @Test
    public void canSetPropertyValueAfterLoad() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR, List.of("empty"));
        AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
        assertThat(provider.hasProperty(polarisEndpoint)).isFalse();
        provider.setValue(polarisEndpoint, "localhost:8182");
        assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8182");
    }

    @Test
    public void cannotSetPropertyValueIfImmutable() {
        AfonPropertyProvider provider = new ImmutablePropertyProvider(new FilePropertyProvider(CONFIG_DIR, List.of("empty")));
        AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
        assertThat(provider.hasProperty(polarisEndpoint)).isFalse();
        provider.setValue(polarisEndpoint, "localhost:8182");
        assertThat(provider.hasProperty(polarisEndpoint)).isFalse();
    }

    @Test
    public void canGetAllProperties() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR);
        assertThat(provider.getProperties()).hasSizeGreaterThan(0);
        try {
            System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, "all");
            assertThat(provider.getProperties()).hasSizeGreaterThan(0);
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }

    @Test
    public void canGetSecretProperties() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR);
        assertThat(provider.getSecretProperties()).hasSizeGreaterThan(0);
    }

    @Test
    public void canGetPropertyByKey() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR);
        assertThat(provider.getValue(AfonProperty.CLUSTER_NAME)).isNotNull();
        assertThat(provider.getValue(AfonProperty.KC_PROTOCOL, "https")).isNotNull();
    }

    @Test
    public void canGetPropertiesOnlyForAWS() {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, "aws");
        try {
            Properties properties = new FilePropertyProvider(CONFIG_DIR).getProperties();
            assertThat(properties.getProperty(AfonProperty.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AWS_REGION.getName()))
                    .as("aws properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AZURE_REGION.getName()))
                    .as("azure properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperty.GCP_BUCKET_NAME.getName()))
                    .as("gcp properties are not loaded")
                    .isNull();
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }

    @Test
    public void canGetPropertiesOnlyForAzure() {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, "azure");
        try {
            Properties properties = new FilePropertyProvider(CONFIG_DIR).getProperties();
            assertThat(properties.getProperty(AfonProperty.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AZURE_REGION.getName()))
                    .as("azure properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AWS_REGION.getName()))
                    .as("aws properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperty.GCP_BUCKET_NAME.getName()))
                    .as("gcp properties are not loaded")
                    .isNull();
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }

    @Test
    public void canGetPropertiesOnlyForGCP() {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, "gcp");
        try {
            Properties properties = new FilePropertyProvider(CONFIG_DIR).getProperties();
            assertThat(properties.getProperty(AfonProperty.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.GCP_BUCKET_NAME.getName()))
                    .as("gcp properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AZURE_REGION.getName()))
                    .as("azure properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperty.AWS_REGION.getName()))
                    .as("aws properties are not loaded")
                    .isNull();
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }

    private void checkAllPropertiesAreSet(Properties properties) {
        for (String property : AfonProperty.getAllNames()) {
            assertThat(properties.get(property)).as("property = %s", property).isNotNull();
            assertThat(properties.get(property)).as("property = %s", property).isNotEqualTo("");
        }
        Set<String> expectedProperties = AfonProperty.getAllNames();
        for (Object property : properties.keySet()) {
            assertTrue(expectedProperties.contains(property.toString()),
                    property + " not in " + expectedProperties);
        }
    }

}

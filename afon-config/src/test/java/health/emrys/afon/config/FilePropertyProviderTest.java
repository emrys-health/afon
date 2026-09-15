package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FilePropertyProviderTest {

    static String TEST_CONFIG_DIR = "src/test/resources/config";
    static String TEMPLATES_CONFIG_DIR = "../templates/config";

    @Test
    public void cannotGetPropertiesWhenFilesAreMissing() {
        AfonPropertyProvider provider = new FilePropertyProvider(TEST_CONFIG_DIR, List.of("missing"));
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
        AfonPropertyProvider provider = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
        assertThat(provider.hasProperty(polarisEndpoint)).isTrue();
        assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8181"); // default value
        provider.setValue(polarisEndpoint, "localhost:8182");
        assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8182"); // overridden value
    }


    @Test
    public void canOverridePropertyValueBySystemPropertyUponLoad() {
        AfonPropertyProvider provider = new FilePropertyProvider(TEST_CONFIG_DIR);
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
        AfonPropertyProvider provider = new FilePropertyProvider(TEST_CONFIG_DIR, List.of("empty"));
        AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
        assertThat(provider.hasProperty(polarisEndpoint)).isFalse();
        provider.setValue(polarisEndpoint, "localhost:8182");
        assertThat(provider.getValue(polarisEndpoint)).isEqualTo("localhost:8182");
    }

    @Test
    public void cannotSetPropertyValueIfImmutable() {
        AfonPropertyProvider provider = new ImmutablePropertyProvider(new FilePropertyProvider(TEST_CONFIG_DIR, List.of("empty")));
        AfonProperty polarisEndpoint = AfonProperty.POLARIS_ENDPOINT;
        assertThat(provider.hasProperty(polarisEndpoint)).isFalse();
        provider.setValue(polarisEndpoint, "localhost:8182");
        assertThat(provider.hasProperty(polarisEndpoint)).isFalse();
    }

    @Test
    public void canGetAllProperties() {
        AfonPropertyProvider provider = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
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
        AfonPropertyProvider provider = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        assertThat(provider.getSecretProperties()).hasSizeGreaterThan(0);
    }

    @Test
    public void canGetPropertyByKey() {
        AfonPropertyProvider provider = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        assertThat(provider.getValue(AfonProperty.CLUSTER_NAME)).isNotNull();
        assertThat(provider.getValue(AfonProperty.OIDC_PROTOCOL, "https")).isNotNull();
    }

    @Test
    public void canGetPropertiesOnlyForAWS() {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, "aws");
        try {
            Properties properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR).getProperties();
            assertThat(properties.getProperty(AfonProperty.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AWS_REGION.getName()))
                    .as("aws properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AZURE_REGION.getName()))
                    .as("azure properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperty.GCP_REGION.getName()))
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
            Properties properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR).getProperties();
            assertThat(properties.getProperty(AfonProperty.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AZURE_REGION.getName()))
                    .as("azure properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.AWS_REGION.getName()))
                    .as("aws properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperty.GCP_REGION.getName()))
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
            Properties properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR).getProperties();
            assertThat(properties.getProperty(AfonProperty.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperty.GCP_REGION.getName()))
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

    @Test
    public void canInterpolatePropertyReferencingAnotherProperty() {
        Properties properties = new FilePropertyProvider("src/test/resources/config", List.of("interpolate")).getProperties();
        assertThat(properties.getProperty("ENV_NAME")).isEqualTo("dev");
        assertThat(properties.getProperty("OIDC_ISSUER_URL")).isEqualTo("https://dev-oauth.click/realms/polaris");
    }

    @Test
    public void canInterpolatePropertyReferencingAnotherPropertyForAws() {
        System.setProperty(ConfigUtils.AFON_CLOUD_PROPERTY, "aws");
        try {
            Properties properties = new FilePropertyProvider("src/test/resources/config", List.of("interpolate")).getProperties();
            assertThat(properties.getProperty("ENV_NAME")).isEqualTo("dev");
            assertThat(properties.getProperty("AWS_POLARIS_DOMAIN_NAME")).isEqualTo("dev-polaris.click");
            assertThat(properties.getProperty("AWS_S3_BUCKET_NAME")).isEqualTo("dev-bucket");
            assertThat(properties.getProperty("AWS_S3_BUCKET_NAME_TRINO")).isEqualTo("dev-bucket-trino");
        } finally {
            System.clearProperty(ConfigUtils.AFON_CLOUD_PROPERTY);
        }
    }

    @Test
    public void canInterpolateUsingSystemPropertyOverride() {
        System.setProperty(AfonProperty.ENV_NAME.getName(), "prod");
        try {
            Properties properties = new FilePropertyProvider("src/test/resources/config", List.of("interpolate")).getProperties();
            assertThat(properties.getProperty("AWS_S3_BUCKET_NAME")).isEqualTo("prod-bucket");
        } finally {
            System.clearProperty(AfonProperty.ENV_NAME.getName());
        }
    }

    @Test
    public void failsWhenInterpolatingUndefinedProperty() {
        AfonPropertyProvider provider = new FilePropertyProvider("src/test/resources/config", List.of("interpolate-undefined"));
        assertThatThrownBy(provider::getProperties)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNDEFINED");
    }

    @Test
    public void failsWhenInterpolatingCircularProperties() {
        AfonPropertyProvider provider = new FilePropertyProvider("src/test/resources/config", List.of("interpolate-circular"));
        assertThatThrownBy(provider::getProperties)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular");
    }

    @Test
    public void ensureTemplatePropertyNamesAreExactlyThoseOfAfonPropertyEnum() {
        // Deliberately excludes the "catalogs" type: catalogs.properties / catalogs-<cloud>.properties
        // hold per-catalog definitions (eg "AWS_BUCKET_NAME_aws_forbidden") keyed by whatever ids
        // <CLOUD>_CATALOG_NAMES is configured with at runtime, so they can't be pre-enumerated in
        // AfonProperty - they only ever make sense as configuration.
        Properties properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR, List.of("afon", "secret")).getProperties();
        // ensure all enum names are found and empty
        Set<String> allNames = AfonProperty.getAllNames();
        for (String name : allNames) {
            assertThat(properties).as("enum name = %s", name).containsKey(name);
        }
        // ensure no names other than enum names are found
        for ( String name : properties.stringPropertyNames() ) {
            assertThat(allNames).as("property name = %s", name).contains(name);
        }
    }

}

package health.emrys.afon.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AfonPropertyTest {

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
    public void canGetAllProperties() {
        AfonPropertyProvider provider = new FilePropertyProvider(CONFIG_DIR);
        Properties properties = provider.getProperties();
        assertThat(properties).hasSizeGreaterThan(0);
        checkAllExpectedInPropertiesFile(properties);
        checkOnlyExpectedInPropertiesFile(properties);
        checkExpectedValuesNotEmpty(properties);
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
        System.setProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY, "aws");
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
            System.clearProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY);
        }
    }

    @Test
    public void canGetPropertiesOnlyForAzure() {
        System.setProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY, "azure");
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
            System.clearProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY);
        }
    }

    @Test
    public void canGetPropertiesOnlyForGCP() {
        System.setProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY, "gcp");
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
            System.clearProperty(ConfigConstants.CLOUD_SYSTEM_PROPERTY);
        }
    }

    private void checkExpectedValuesNotEmpty(Properties properties) {
        for (String property : AfonProperty.getAllNames()) {
            assertThat(properties.get(property)).isNotEqualTo("");
        }
    }

    private static void checkOnlyExpectedInPropertiesFile(Properties properties) {
        Set<String> expectedProperties = AfonProperty.getAllNames();
        for (Object property : properties.keySet()) {
            assertTrue(
                    expectedProperties.contains(property.toString()),
                    property + " not in " + expectedProperties);
        }
    }

    private static void checkAllExpectedInPropertiesFile(Properties properties) {
        for (String property : AfonProperty.getAllNames()) {
            assertThat(properties.get(property)).as("property = %s", property).isNotNull();
        }
    }


}

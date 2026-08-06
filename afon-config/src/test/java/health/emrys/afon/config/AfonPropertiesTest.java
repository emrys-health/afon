package health.emrys.afon.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AfonPropertiesTest {
    @BeforeAll
    public static void initializeConstants() throws ClassNotFoundException {
        Class.forName(AfonProperties.class.getName());
    }

    @Test
    public void testLoadPropertiesWhenFilesAreMissing(){
        AfonPropertiesLoader loader = new AfonPropertiesLoader(List.of("missing"));
        try {
            loader.getProperties();
        } catch ( RuntimeException e ){
            assertThat(e.getMessage()).endsWith("missing.properties (No such file or directory)");
        }
    }

    @Test
    public void testRetrieveAllPropertyNames() {
        assertThat(AfonProperties.getAllNames()).hasSizeGreaterThan(0);
    }

    @Test
    public void testLoadAllProperties() throws IOException {
        AfonPropertiesLoader loader = new AfonPropertiesLoader();
        Properties properties = loader.getProperties();
        assertThat(properties).hasSizeGreaterThan(0);
        checkAllExpectedInPropertiesFile(properties);
        checkOnlyExpectedInPropertiesFile(properties);
        checkExpectedValuesNotEmpty(properties);
        assertThat(loader.getSecretProperties()).hasSizeGreaterThan(0);
    }

    @Test
    public void testLoadAWSPropertiesOnly() throws IOException {
        System.setProperty(AfonPropertiesLoader.CLOUD_SYSTEM_PROPERTY, "aws");
        try {
            Properties properties = new AfonPropertiesLoader().getProperties();
            assertThat(properties.getProperty(AfonProperties.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperties.AWS_REGION.getName()))
                    .as("aws properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperties.AZURE_REGION.getName()))
                    .as("azure properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperties.GCP_BUCKET_NAME.getName()))
                    .as("gcp properties are not loaded")
                    .isNull();
        } finally {
            System.clearProperty(AfonPropertiesLoader.CLOUD_SYSTEM_PROPERTY);
        }
    }

    @Test
    public void testLoadAzurePropertiesOnly() throws IOException {
        System.setProperty(AfonPropertiesLoader.CLOUD_SYSTEM_PROPERTY, "azure");
        try {
            Properties properties = new AfonPropertiesLoader().getProperties();
            assertThat(properties.getProperty(AfonProperties.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperties.AZURE_REGION.getName()))
                    .as("azure properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperties.AWS_REGION.getName()))
                    .as("aws properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperties.GCP_BUCKET_NAME.getName()))
                    .as("gcp properties are not loaded")
                    .isNull();
        } finally {
            System.clearProperty(AfonPropertiesLoader.CLOUD_SYSTEM_PROPERTY);
        }
    }

    @Test
    public void testLoadGcpPropertiesOnly() throws IOException {
        System.setProperty(AfonPropertiesLoader.CLOUD_SYSTEM_PROPERTY, "gcp");
        try {
            Properties properties = new AfonPropertiesLoader().getProperties();
            assertThat(properties.getProperty(AfonProperties.CLUSTER_NAME.getName()))
                    .as("common properties are still loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperties.GCP_BUCKET_NAME.getName()))
                    .as("gcp properties are loaded")
                    .isNotNull();
            assertThat(properties.getProperty(AfonProperties.AZURE_REGION.getName()))
                    .as("azure properties are not loaded")
                    .isNull();
            assertThat(properties.getProperty(AfonProperties.AWS_REGION.getName()))
                    .as("aws properties are not loaded")
                    .isNull();
        } finally {
            System.clearProperty(AfonPropertiesLoader.CLOUD_SYSTEM_PROPERTY);
        }
    }

    private void checkExpectedValuesNotEmpty(Properties properties) {
        for (String property : AfonProperties.getAllNames()) {
            assertThat(properties.get(property)).isNotEqualTo("");
        }
    }

    private static void checkOnlyExpectedInPropertiesFile(Properties properties) {
        Set<String> expectedProperties = AfonProperties.getAllNames();
        for (Object property : properties.keySet()) {
            assertTrue(
                    expectedProperties.contains(property.toString()),
                    property.toString() + " not in " + expectedProperties);
        }
    }

    private static void checkAllExpectedInPropertiesFile(Properties properties) {
        for (String property : AfonProperties.getAllNames()) {
            assertThat(properties.get(property)).as("property = %s", property).isNotNull();
        }
    }

}
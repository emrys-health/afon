package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.PolarisConstants;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.config.ConfigUtils.cloudPropertySet;
import static health.emrys.afon.config.ConfigUtils.cloudPropertyValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThat;

class PolarisConfigTest {
    static String TEMPLATES_CONFIG_DIR = "../templates/config";

    @Test
    public void canGenerateConfigScriptForGivenCloud() {
        AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        for (AfonCloud cloud : AfonCloud.values()) {
            validateScriptFor(cloud, properties);
        }
    }

    private void validateScriptFor(AfonCloud cloud, AfonPropertyProvider properties) {
        if ( cloudPropertySet() && !cloudPropertyValue().equalsIgnoreCase(cloud.toString()) ){
            // does not match currently specified cloud
            return;
        }
        String deploymentId = cloud.toString();
        String script = PolarisConfig.getProviderCredentials(properties, deploymentId);
        String polarisDbName = "polaris_db_" + PolarisConfig.polarisDomainName(properties, deploymentId).replace(".", "_");
        assertThat(script).contains(polarisDbName);
        PolarisConstants.getPolarisProperties(cloud)
                .forEach((property, envName) -> {
                    assertThat(script).contains(envName);
                    assertThat(script).contains(properties.getValue(property));
                });
    }

    @Test
    public void canGenerateConfigScriptWithOnlyDeploymentCloudProperties(@TempDir Path configDir) throws IOException {
        assumeTrue(!cloudPropertySet() || cloudPropertyValue().equalsIgnoreCase("azure"));
        copyTemplates(configDir, "afon.properties", "secret.properties", "catalogs.properties",
                      "afon-azure.properties", "secret-azure.properties");
        AfonPropertyProvider properties = new FilePropertyProvider(configDir.toString());
        properties.setValue(AZURE_APP_ID, "azure-app-id");

        String script = PolarisConfig.getProviderCredentials(properties, "azure");

        assertThat(script).contains("export AZURE_CLIENT_ID=azure-app-id");
        assertThat(script).doesNotContain(AWS_ACCESS_KEY_ID.getName());
        assertThat(script).doesNotContain(GOOGLE_APPLICATION_CREDENTIALS.getName());
    }

    @Test
    public void canGenerateConfigScriptWithOtherCloudPropertiesIfPresent(@TempDir Path configDir) throws IOException {
        assumeTrue(!cloudPropertySet());
        copyTemplates(configDir, "afon.properties", "secret.properties", "catalogs.properties",
                      "afon-azure.properties", "secret-azure.properties", "secret-aws.properties");
        AfonPropertyProvider properties = new FilePropertyProvider(configDir.toString());
        properties.setValue(AWS_ACCESS_KEY_ID, "aws-key-id");
        properties.setValue(GOOGLE_APPLICATION_CREDENTIALS, "{}");

        String script = PolarisConfig.getProviderCredentials(properties, "azure");

        assertThat(script).contains("export AWS_ACCESS_KEY_ID=aws-key-id");
        assertThat(script).doesNotContain(AWS_SECRET_ACCESS_KEY.getName()); // blank in templates
        assertThat(script).contains("export GOOGLE_APPLICATION_CREDENTIALS=");
    }

    private static void copyTemplates(Path configDir, String... files) throws IOException {
        for (String file : files) {
            Files.copy(Path.of(TEMPLATES_CONFIG_DIR, file), configDir.resolve(file));
        }
    }

    @Test
    public void cannotGenerateConfigScriptForUnsupportedCloud() {
        AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        try {
            PolarisConfig.getProviderCredentials(properties, "unsupported");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).containsIgnoringCase("unsupported");
        }
    }

    @Test
    public void canGenerateKeyCloakExportsConfig() {
        Map<String, String> properties = KeyCloakHelper.polarisOidcEnv(
            "host",
        "username",
        "password");
        String exports = PolarisConfig.toExports(properties);
        String[] lines = exports.split("\n");
        assertThat(lines.length).isEqualTo(properties.size());
        String export = "export ";
        for (String line : lines) {
            assertThat(line).startsWith(export);
            String envName = line.substring(
                "export ".length(),
                line.indexOf('='));
            assertThat(envName).isEqualTo(envName.toUpperCase());
        }
    }
}
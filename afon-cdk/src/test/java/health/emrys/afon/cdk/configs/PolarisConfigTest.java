package health.emrys.afon.cdk.configs;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolarisConfigTest {
    static String TEMPLATES_CONFIG_DIR = "../templates/config";

    @Test
    public void canGenerateConfigScriptForGivenCloud() {
        AfonPropertyProvider properties = new FilePropertyProvider(TEMPLATES_CONFIG_DIR);
        properties.setValue(AZURE_APP_SECRET, "azure-app-secret"); // blank in templates, but required without a workload identity
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
        properties.setValue(AZURE_APP_SECRET, "azure-app-secret");

        String script = PolarisConfig.getProviderCredentials(properties, "azure");

        assertThat(script).contains("export AZURE_CLIENT_ID='azure-app-id'");
        assertThat(script).contains("export AZURE_CLIENT_SECRET='azure-app-secret'");
        assertThat(script).doesNotContain(AWS_ACCESS_KEY_ID.getName());
        assertThat(script).doesNotContain(GOOGLE_APPLICATION_CREDENTIALS.getName());
    }

    @Test
    public void omitsClientSecretForWorkloadIdentity(@TempDir Path configDir) throws IOException {
        assumeTrue(!cloudPropertySet() || cloudPropertyValue().equalsIgnoreCase("azure"));
        copyTemplates(configDir, "afon.properties", "secret.properties", "catalogs.properties",
                      "afon-azure.properties", "secret-azure.properties");
        AfonPropertyProvider properties = new FilePropertyProvider(configDir.toString());
        properties.setValue(AZURE_APP_ID, "azure-app-id");
        properties.setValue(AZURE_WORKLOAD_IDENTITY, "true");

        String script = PolarisConfig.getProviderCredentials(properties, "azure");

        assertThat(script).contains("export AZURE_CLIENT_ID='azure-app-id'");
        assertThat(script).doesNotContain("AZURE_CLIENT_SECRET");
    }

    @Test
    public void worksWithoutTheSecretPropertyForWorkloadIdentity(@TempDir Path configDir) throws IOException {
        assumeTrue(!cloudPropertySet() || cloudPropertyValue().equalsIgnoreCase("azure"));
        copyTemplates(configDir, "afon.properties", "secret.properties", "catalogs.properties",
                      "afon-azure.properties", "secret-azure.properties");
        // as if commented out, ie no AZURE_APP_SECRET at all, not blank
        Path secrets = configDir.resolve("secret-azure.properties");
        java.nio.file.Files.write(secrets, java.nio.file.Files.readAllLines(secrets).stream()
                                            .filter(line -> !line.startsWith("AZURE_APP_SECRET=")).toList());
        AfonPropertyProvider properties = new FilePropertyProvider(configDir.toString());
        properties.setValue(AZURE_APP_ID, "azure-app-id");
        properties.setValue(AZURE_WORKLOAD_IDENTITY, "true");

        String script = PolarisConfig.getProviderCredentials(properties, "azure");

        assertThat(script).contains("export AZURE_CLIENT_ID='azure-app-id'");
        assertThat(script).doesNotContain("AZURE_CLIENT_SECRET");
    }

    @Test
    public void rejectsBlankClientSecretWithoutWorkloadIdentity(@TempDir Path configDir) throws IOException {
        assumeTrue(!cloudPropertySet() || cloudPropertyValue().equalsIgnoreCase("azure"));
        copyTemplates(configDir, "afon.properties", "secret.properties", "catalogs.properties",
                      "afon-azure.properties", "secret-azure.properties");
        AfonPropertyProvider properties = new FilePropertyProvider(configDir.toString());
        properties.setValue(AZURE_APP_ID, "azure-app-id");

        assertThatThrownBy(() -> PolarisConfig.getProviderCredentials(properties, "azure"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AZURE_WORKLOAD_IDENTITY");
    }

    @Test
    public void canGenerateConfigScriptWithOtherCloudPropertiesIfPresent(@TempDir Path configDir) throws IOException {
        assumeTrue(!cloudPropertySet());
        copyTemplates(configDir, "afon.properties", "secret.properties", "catalogs.properties",
                      "afon-azure.properties", "secret-azure.properties", "secret-aws.properties");
        AfonPropertyProvider properties = new FilePropertyProvider(configDir.toString());
        properties.setValue(AZURE_APP_SECRET, "azure-app-secret");
        properties.setValue(AWS_ACCESS_KEY_ID, "aws-key-id");
        properties.setValue(GOOGLE_APPLICATION_CREDENTIALS, "{}");

        String script = PolarisConfig.getProviderCredentials(properties, "azure");

        assertThat(script).contains("export AWS_ACCESS_KEY_ID='aws-key-id'");
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

    @Test
    public void aProviderTrustsTheIssuerTheAudienceAndTheAcrOfItsTokensAndTakesItsRolesFromTheProject() {
        Map<String, String> properties = KeyCloakHelper.polarisOidcEnv(
            "http://keycloak/realms/polaris", "id", "secret",
            new KeyCloakHelper.Trust("https://id.example.org/realms/polaris", "provider-lon", "mfa", "project"));
        Map<String, String> env = new java.util.HashMap<>();
        properties.forEach((k, v) -> env.put(PolarisConfig.toEnvironmentVariable(k), v));

        assertThat(env).containsEntry("QUARKUS_OIDC_TOKEN_ISSUER", "https://id.example.org/realms/polaris");
        assertThat(env).containsEntry("QUARKUS_OIDC_TOKEN_AUDIENCE", "provider-lon");
        assertThat(env).containsEntry("QUARKUS_OIDC_TOKEN_REQUIRED_CLAIMS__ACR_", "mfa");
        assertThat(env).containsEntry("QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH", "project");
        assertThat(env).containsEntry("POLARIS_OIDC_PRINCIPAL_ROLES_MAPPER_MAPPINGS_0__REPLACEMENT", "PRINCIPAL_ROLE:$1Project");
    }

    @Test
    public void theNamesOfTheVariablesAreThoseOfSmallRye() {
        assertThat(PolarisConfig.toEnvironmentVariable("a.b-c[0].d\"e\"")).isEqualTo("A_B_C_0__D_E_");
    }

    @Test
    public void aProviderTrustReachesThePodAsItIsWhenTheExportsAreSourcedByAShell() throws Exception {
        String exports = PolarisConfig.trustExports("http://keycloak/realms/polaris", "id", "secret",
            new KeyCloakHelper.Trust("https://id.example.org/realms/polaris", "provider-lon", "mfa", "project"));
        Process shell = new ProcessBuilder("bash", "-c", exports + "\nenv").redirectErrorStream(true).start();
        String env = new String(shell.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(shell.waitFor()).as(env).isZero();
        assertThat(env.lines().toList())
            .contains("POLARIS_OIDC_PRINCIPAL_ROLES_MAPPER_MAPPINGS_0__REGEX=(.+)",
                      "POLARIS_OIDC_PRINCIPAL_ROLES_MAPPER_MAPPINGS_0__REPLACEMENT=PRINCIPAL_ROLE:$1Project",
                      "QUARKUS_OIDC_TOKEN_REQUIRED_CLAIMS__ACR_=mfa",
                      "QUARKUS_OIDC_TOKEN_AUDIENCE=provider-lon");
    }

    @Test
    public void everyOtherPolarisIsExportedAsItWas() {
        Map<String, String> plain = KeyCloakHelper.polarisOidcEnv("http://keycloak/realms/polaris", "id", "secret");
        assertThat(PolarisConfig.toExports(plain)).doesNotContain("'");
    }
}

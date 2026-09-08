package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.PolarisConstants;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static health.emrys.afon.config.ConfigUtils.afonCloud;
import static org.assertj.core.api.Assertions.assertThat;

class PolarisConfigTest {

    @Test
    public void canGenerateConfigScriptForGivenCloud() {
        AfonPropertyProvider properties = new FilePropertyProvider();
        for (AfonCloud cloud : AfonCloud.values()) {
            validateScriptFor(cloud, properties);
        }
    }

    private void validateScriptFor(AfonCloud cloud, AfonPropertyProvider properties) {
        if ( !afonCloud().equalsIgnoreCase(cloud.toString()) ){
            // does not match currently specified cloud
            return;
        }
        String deploymentId = cloud.toString();
        String script = PolarisConfig.getProviderCredentials(properties, deploymentId);
        String polarisDbName = "polaris_db_" + PolarisConfig.polarisDomainName(properties, deploymentId).replace(".", "_");
        assertThat(script).contains(polarisDbName);
        PolarisConstants.getPolarisProperties()
                .forEach(x -> {
                    assertThat(script).contains(x.getName());
                    assertThat(script).contains(properties.getValue(x));
                });
    }

    @Test
    public void cannotGenerateConfigScriptForUnsupportedCloud() {
        AfonPropertyProvider properties = new FilePropertyProvider();
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
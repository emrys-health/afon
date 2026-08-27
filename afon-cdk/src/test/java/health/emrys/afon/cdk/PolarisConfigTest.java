package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.PolarisConstants;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static health.emrys.afon.config.AfonCloud.*;
import static org.assertj.core.api.Assertions.assertThat;

class PolarisConfigTest {

    @Test
    public void canGenerateConfigScriptForGivenCloud() {
        AfonPropertyProvider properties = new FilePropertyProvider();
        validateScriptFor(AWS.toString(), properties);
        validateScriptFor(AZURE.toString(), properties);
        validateScriptFor(GCP.toString(), properties);
    }

    private void validateScriptFor(String id, AfonPropertyProvider properties) {
        String script = PolarisConfig.getProviderCredentials(properties, id);
        String polarisDbName = "polaris_db_" + PolarisConfig.polarisDomainName(properties, id).replace(".", "_");
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
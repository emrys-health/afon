package health.emrys.afon.cdk;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.PolarisConstants;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarisConfigTest {

    @Test
    public void testConfigScript() {
        Properties           properties = new FilePropertyProvider().getProperties();
        String               script     = PolarisConfig.getProviderCredentials(properties, "test");
        PolarisConstants.getPolarisProperties()
            .forEach(x -> {
                String propertyName = x.getName();
                assertTrue(script.contains(propertyName), propertyName);
                assertTrue(script.contains(x.getValueFrom(properties)), propertyName);
            });
    }

    @Test
    public void testKeyCloakConfig() {
        Map<String, String> properties = KeyCloakHelper.polarisOidcEnv(
            "host",
        "username",
        "password");
        String exports = PolarisConfig.toExports(properties);
        String[] lines = exports.split("\n");
        assertEquals(lines.length, properties.size());
        String export = "export ";
        for (String line : lines) {
            assertTrue(line.startsWith(export));
            String envName = line.substring(
                "export ".length(),
                line.indexOf('='));
            assertEquals(envName, envName.toUpperCase());
        }
    }
}
package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.PolarisConstants;
import health.emrys.afon.polaris.keycloak.KeyCloakHelper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarisConfigTest {
    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisConfigTest.class);

    @Test
    public void testConfigScript() {
        AfonPropertiesLoader loader     = new AfonPropertiesLoader();
        Properties           properties = loader.getProperties();
        String               script     = PolarisConfig.getProviderCredentials(properties, "test");
        logger.info("\n" + script);
        PolarisConstants.POLARIS_PROPERTIES
            .forEach(x -> {
                String propertyName = x.getPropertyName();
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
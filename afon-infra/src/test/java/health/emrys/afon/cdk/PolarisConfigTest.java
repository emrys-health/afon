package health.emrys.afon.cdk;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.PolarisConstants;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PolarisConfigTest {
    static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisConfigTest.class);

    @Test
    public void testConfigScript() throws IOException {
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
}
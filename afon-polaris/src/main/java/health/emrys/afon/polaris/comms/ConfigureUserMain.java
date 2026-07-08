package health.emrys.afon.polaris.comms;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;
import static health.emrys.afon.config.AfonProperties.USER_POLARIS_ID;

public class ConfigureUserMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureUserMain.class);

    private final PolarisConfigurer configurer;

    public static void main(String[] args) {
        Properties        properties    = new AfonPropertiesLoader().getProperties();
        if (args.length > 0) {
            String endpoint = args[0];
            logger.info("Using endpoint {}", endpoint);
            properties.put(POLARIS_ENDPOINT.getPropertyName(), endpoint);
        }
        ConfigureUserMain app           = new ConfigureUserMain(properties);
        String            principalName = args.length > 1 ? args[1] : USER_POLARIS_ID.getValueFrom(properties);
        try {
            logger.info("Configuring Polaris User {}", principalName);
            app.configureUser(principalName);
        } catch (Exception x) {
            logger.error("Error while configuring user " + principalName, x);
        }
    }

    private final String accessToken;

    ConfigureUserMain(Properties properties) {
        AfonPolarisClient client = AfonPolarisClientFactory.clientFactory().getPolarisClient(properties);
        accessToken = client.getAccessToken();
        logger.debug("Using access token {}", accessToken);
        configurer  = new PolarisConfigurer(client);
    }

    private void configureUser(String principalName) throws IncorrectConfigException, IOException {
        PrincipalWithCredentials principal = configurer.configureUser(accessToken, principalName);
        logger.info("Created Polaris User {}", principal);
    }
}

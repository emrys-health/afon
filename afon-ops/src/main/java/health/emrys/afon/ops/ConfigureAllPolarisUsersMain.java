package health.emrys.afon.ops;

import health.emrys.afon.config.AfonUsers;
import health.emrys.afon.config.AfonPropertiesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

import static health.emrys.afon.config.AfonProperties.POLARIS_ENDPOINT;

public class ConfigureAllPolarisUsersMain {

    private static final Logger logger = LoggerFactory.getLogger(ConfigureAllPolarisUsersMain.class);

    public static void main(String[] args) {
        Properties properties = new AfonPropertiesLoader().getProperties();
        if (args.length > 0) {
            properties.put(POLARIS_ENDPOINT.getName(), args[0]);
        }
        ConfigurePolarisUserMain app         = new ConfigurePolarisUserMain(properties);
        String            catalogName = args[1];
        AfonUsers afonUsers = new AfonUsers();
        try {
            app.configurePrivilegesFor(catalogName);
            app.configureAll(catalogName, afonUsers);
        } catch (Exception e) {
            logger.error("Error configuring polaris users " + afonUsers, e);
        }
    }

}

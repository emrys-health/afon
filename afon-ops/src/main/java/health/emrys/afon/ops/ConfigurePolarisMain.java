package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.ConfigurablePolarisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurePolarisMain {

    static final Logger logger = LoggerFactory.getLogger(ConfigurePolarisMain.class);

    public static void main(String[] args) {
        try {
            AfonPropertyProvider provider = new FilePropertyProvider();
            ConfigurablePolarisClient client = new ConfigurablePolarisClient(provider.getProperties());
            if (args.length == 1) { // credentials
                client.configureAllCatalogs(args[0]);
            }
        } catch (Exception e) {
            logger.error("Error configuring polaris", e);
        }
    }

}
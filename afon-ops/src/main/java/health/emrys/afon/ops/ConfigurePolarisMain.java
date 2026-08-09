package health.emrys.afon.ops;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.config.PolarisConfigClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class ConfigurePolarisMain {

    static final Logger logger = LoggerFactory.getLogger(ConfigurePolarisMain.class);

    public static void main(String[] args) {
        try {
            PolarisConfigClient client = new PolarisConfigClient();
            if (args.length == 0) {
                logger.info("Running smoke test");
                client.smokeTest();
            } else {
                AfonPropertyProvider provider = new FilePropertyProvider();
                Properties properties;
                if (args.length == 1) {
                    properties = provider.getProperties();
                } else {
                    properties = provider.getProperties(args[1]);
                }
                client.createAllCatalogs(properties, args[0]);
            }
        } catch (Exception e) {
            logger.error("Error configuring polaris", e);
        }
    }

}
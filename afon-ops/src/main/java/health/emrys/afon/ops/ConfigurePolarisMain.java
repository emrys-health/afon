package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertiesLoader;
import health.emrys.afon.polaris.config.PolarisConfigClient;
import org.slf4j.Logger;

import java.util.Properties;

public class ConfigurePolarisMain {

    static final Logger logger = org.slf4j.LoggerFactory.getLogger(ConfigurePolarisMain.class);

    public static void main(String[] args) {
        try {
            PolarisConfigClient client = new PolarisConfigClient();
            if (args.length == 0) {
                logger.info("Running smoke test");
                client.smokeTest();
            } else {
                AfonPropertiesLoader loader = null;
                if (args.length == 1) {
                    loader = new AfonPropertiesLoader();
                } else {
                    loader = new AfonPropertiesLoader(args[1]);
                }
                Properties props = loader.getProperties();
                client.createAllCatalogs(props, args[0]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
package health.emrys.afon.ops;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisCatalogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurePolarisCatalogsMain {

    static final Logger logger = LoggerFactory.getLogger(ConfigurePolarisCatalogsMain.class);

    public static void main(String[] args) {
        try {
            AfonPropertyProvider provider = new FilePropertyProvider();
            PolarisCatalogClient client = new PolarisCatalogClient(provider.getProperties());
            if (args.length == 1) { // credentials
                client.configureAllCatalogs(args[0]);
            }
        } catch (Exception e) {
            logger.error("Failed configuring polaris catalogs", e);
        }
    }

}
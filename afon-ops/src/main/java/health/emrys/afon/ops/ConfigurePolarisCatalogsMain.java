package health.emrys.afon.ops;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisCatalogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurePolarisCatalogsMain {

    static final Logger logger = LoggerFactory.getLogger(ConfigurePolarisCatalogsMain.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            logger.warn("{} requires credentials as first arg", ConfigurePolarisCatalogsMain.class.getName());
            return;
        }
        String credentials = args[0];
        try {
            var properties = new FilePropertyProvider();
            new PolarisCatalogClient(properties).configureAllCatalogs(credentials);
        } catch (Exception e) {
            logger.error("Failed to configure polaris catalogs", e);
            throw new RuntimeException(e);
        }
    }

}
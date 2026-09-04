package health.emrys.afon.ops.smoke;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisCatalogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;

/**
 * Given an endpoint retrieved from property {@link AfonProperty#POLARIS_ENDPOINT},
 * allows the configuration of the Polaris catalogs for that endpoint.
 * The property can be defined in file properties, or overridden via system property "POLARIS_ENDPOINT".
 *
 * A usage example:
 * <pre>
 ./afon.sh -a exec -ec ops.smoke.PolarisCatalogSmoke -ea "AZURE_EXTERNAL" -ee "POLARIS_ENDPOINT=emryspolarisazure.click" -lc INFO
 * </pre>
 */
public class PolarisCatalogSmoke {

    private static final Logger logger = LoggerFactory.getLogger(PolarisCatalogSmoke.class);

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        PolarisCatalogClient polarisClient = new PolarisCatalogClient(properties);
        try {
            if (args.length == 0) {
                polarisClient.configureAllCatalogs();
            } else if (args.length == 1) {
                String catalogName = args[0];
                polarisClient.configureCatalogs(catalogName);
            } else if (args.length == 2) {
                String catalogName = args[0];
                String credentials = args[1];
                logger.debug("Credentials: " + credentials);
                String[] creds = credentials.split(":");
                if (creds.length != 2) {
                    logger.error("Incorrect access token {}. The token should have a ':' delimiting ID from Secret", credentials);
                }
                properties.setValue(POLARIS_ID, creds[0]);
                properties.setValue(POLARIS_SECRET, creds[1]);
                polarisClient.configureCatalogs(catalogName);
            } else {
                String usage = String.format("""
                    
                    Invalid arguments: '%s'
                    usage:  %s supports the following args options:
                            [catalog-name] [polaris_id:polaris_secret]
                            no args:  configures all catalogs
                            <catalog-name>:  configures named catalog
                            <catalog-name> <polaris_id:polaris_secret>: configures named catalog with credentials provided                    
                    """, String.join(" ", args), PolarisCatalogSmoke.class.getSimpleName());
                logger.warn(usage);
            }
        } catch (Exception e) {
            logger.warn("Failed to configure Polaris catalogs", e);
        }
    }

}
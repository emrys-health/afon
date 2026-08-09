package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperties.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.AfonPolarisClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.polaris.config.PolarisConfigurer.catalogsURI;

public class PurgePolarisMain {
    private static final Logger logger = LoggerFactory.getLogger(PurgePolarisMain.class);

    public static void main(String[] args) {
        if (args.length > 0) {
            String domainName = args[0];
            try {
                AfonPolarisClientFactory clientFactory = AfonPolarisClientFactory.clientFactory();
                var client = clientFactory.getPolarisClient(domainName, new FilePropertyProvider().getProperties());
                String accessToken = client.getAccessToken();
                for (AfonCloud warehouse : AfonCloud.values()) {
                    client.delete(catalogsURI(warehouse.toString()), accessToken);
                }
            } catch (Exception e) {
                logger.error("Failed to purge polaris for domain {}", domainName, e);
                throw new RuntimeException(e);
            }
        } else {
            logger.error("Domain name required as first arg");
        }
    }
}

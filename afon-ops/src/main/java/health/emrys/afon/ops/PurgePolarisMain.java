package health.emrys.afon.ops;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static health.emrys.afon.polaris.config.PolarisConfigurer.catalogsURI;

public class PurgePolarisMain {
    private static final Logger logger = LoggerFactory.getLogger(PurgePolarisMain.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.printf("%s requires argument: domain-name", PurgePolarisMain.class.getSimpleName());
            return;
        }
        String domainName = args[0];
        try {
            var properties = new FilePropertyProvider();
            var client = PolarisRestClientFactory.clientFactory().createClient(domainName, properties);
            String accessToken = client.getAccessToken();
            for (AfonCloud warehouse : AfonCloud.values()) {
                client.delete(catalogsURI(warehouse.toString()), accessToken);
            }
        } catch (Exception e) {
            logger.error("Failed to purge polaris for domain {}", domainName, e);
            throw new RuntimeException(e);
        }
    }
}

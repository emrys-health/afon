package health.emrys.afon.ops;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Ths was created in a rush for the demo TODO refactor the code copy-and-pasted from PurgeAndPopulatePolarisMain
 */
public class PurgePolarisSingleMain {
    private static final Logger logger = LoggerFactory.getLogger(PurgePolarisMain.class);

    public static void main(String[] args) {
        if (args.length == 2) {
            String domainName  = args[0];
            String catalogName = args[1];
            try {
                new PurgePolarisSingleMain(domainName).purge(catalogName);
            } catch (Exception e) {
                logger.error("Failed to purge polaris for domain {}", domainName, e);
                throw new RuntimeException(e);
            }
        } else {
            logger.error("Domain name and catalog required as first arg");
        }
    }

    private final Purger purger;

    private PurgePolarisSingleMain(String domainName) {
        PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
        PolarisRestClient client          = clientFactory.createClient(domainName, new FilePropertyProvider().getProperties());
        String accessToken     = client.getAccessToken();
        purger = new Purger(client, accessToken, domainName);
    }

    private void purge(String catalogName) throws IOException, ParseException {
        purger.purge(catalogName);
    }

}

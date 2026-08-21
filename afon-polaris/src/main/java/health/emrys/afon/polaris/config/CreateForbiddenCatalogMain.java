package health.emrys.afon.polaris.config;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

/**
 * This was created in a rush for the demo TODO we need a better way of doing this. possibly delete this
 */
public class CreateForbiddenCatalogMain {

    private static final Logger logger = LoggerFactory.getLogger(CreateForbiddenCatalogMain.class);

    public static void main(String[] args) {
        if (args.length == 1) {
            String domainName  = args[0];
            try {
                new CreateForbiddenCatalogMain(domainName).create();
            } catch (Exception e) {
                logger.error("Failed to create 'forbidden' bucket for domain {}", domainName, e);
                throw new RuntimeException(e);
            }
        } else {
            logger.error("Domain name required as first arg");
        }
    }

    private final PolarisRestClient client;
    private final String            accessToken;
    private final String            domainName;

    private CreateForbiddenCatalogMain(String domainName) {
        PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
        client          = clientFactory.createClient(domainName, new FilePropertyProvider().getProperties());
        accessToken     = client.getAccessToken();
        this.domainName = domainName;
    }


    private void create() throws IncorrectConfigException, IOException {
        Properties properties = new FilePropertyProvider().getProperties();
        PolarisConfigurer configurer = new PolarisConfigurer(client);
        configurer.createCatalog(new AwsPolarisCatalogNonPermissioned(properties));
    }
}

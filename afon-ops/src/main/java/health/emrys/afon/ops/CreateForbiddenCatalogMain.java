package health.emrys.afon.ops;

import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsForbiddenPolarisCatalog;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisConfigurer;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;

/**
 * This was created in a rush for the demo
 * @Deprecated Functionality now in ConfigurePolarisCatalogsMain
 * AwsForbiddenPolarisCatalog handled in PolarisCatalogClient.configureAllCatalogs() with #adminGrants() returning {@code false}.
 */
public class CreateForbiddenCatalogMain {

    private static final Logger logger = LoggerFactory.getLogger(CreateForbiddenCatalogMain.class);

    private final PolarisRestClient client;

    private CreateForbiddenCatalogMain(String domainName) {
        PolarisRestClientFactory clientFactory = PolarisRestClientFactory.clientFactory();
        client = clientFactory.createClient(domainName, new FilePropertyProvider().getProperties());
    }

    private void create() throws IncorrectConfigException, IOException {
        Properties properties = new FilePropertyProvider().getProperties();
        PolarisConfigurer configurer = new PolarisConfigurer(client);
        configurer.createCatalog(new AwsForbiddenPolarisCatalog(properties));
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            logger.error("{} requires domain name as first arg", CreateForbiddenCatalogMain.class.getName());
            return;
        }
        String domainName  = args[0];
        try {
            new CreateForbiddenCatalogMain(domainName).create();
        } catch (Exception e) {
            logger.error("Failed to create 'forbidden' bucket for domain {}", domainName, e);
            throw new RuntimeException(e);
        }
    }


}

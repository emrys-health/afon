package health.emrys.afon.ops;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import health.emrys.afon.polaris.catalogs.AwsForbiddenPolarisCatalog;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.config.PolarisConfigurer;
import health.emrys.afon.polaris.config.PolarisRestClient;
import health.emrys.afon.polaris.config.PolarisRestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * This was created in a rush for the demo
 * @Deprecated Functionality now in ConfigurePolarisCatalogsMain
 * AwsForbiddenPolarisCatalog handled in PolarisCatalogClient.configureAllCatalogs() with #adminGrants() returning {@code false}.
 */
public class CreateForbiddenCatalogMain {

    private static final Logger logger = LoggerFactory.getLogger(CreateForbiddenCatalogMain.class);

    private final PolarisRestClient client;
    private final AfonPropertyProvider properties;

    public CreateForbiddenCatalogMain(AfonPropertyProvider properties) {
        this.properties = properties;
        client = PolarisRestClientFactory.clientFactory().createClient(properties);
    }

    private void create() throws IncorrectConfigException, IOException {
        PolarisConfigurer configurer = new PolarisConfigurer(client);
        configurer.createCatalog(new AwsForbiddenPolarisCatalog(properties));
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.printf("%s requires arguments: domain-name", CreateForbiddenCatalogMain.class.getSimpleName());
            return;
        }
        String domainName  = args[0];
        try {
            var properties = new FilePropertyProvider();
            properties.setValue(AfonProperty.POLARIS_ENDPOINT, domainName);
            new CreateForbiddenCatalogMain(properties).create();
        } catch (Exception e) {
            logger.error("Failed to create 'forbidden' bucket for domain {}", domainName, e);
            throw new RuntimeException(e);
        }
    }


}

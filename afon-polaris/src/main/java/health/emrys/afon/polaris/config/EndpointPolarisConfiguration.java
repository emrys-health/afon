package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.*;
import static health.emrys.afon.polaris.config.PolarisCatalogClient.addPolarisCredentialsTo;
import static health.emrys.afon.polaris.config.PolarisRestClient.failuresOf;

/**
 * Given an endpoint, {@link EndpointPolarisConfiguration} allows the configuration of the Polaris catalogs for that
 * endpoint.  The endpoint is retrieved from the {@link AfonProperty#POLARIS_ENDPOINT} property.
 *
 * The credentials are retrieved from the {@link AfonProperty#POLARIS_ID} and {@link AfonProperty#POLARIS_SECRET}.
 */
public class EndpointPolarisConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(EndpointPolarisConfiguration.class);
    private final AfonPropertyProvider properties;
    private final PolarisCatalogClient polarisClient;
    private final String credentials;

    public EndpointPolarisConfiguration(AfonPropertyProvider properties) {
        this.properties = properties;
        this.polarisClient = new PolarisCatalogClient(properties);
        this.credentials = properties.getValue(AfonProperty.POLARIS_ID) + ":" + properties.getValue(AfonProperty.POLARIS_SECRET);
        logger.info("Using Polaris endpoint: {}", properties.getValue(POLARIS_ENDPOINT));
    }

    /**
     * Configures all catalogs
     * @throws IncorrectConfigException
     * @throws IOException
     */
    public void configure() throws IncorrectConfigException, IOException {
        configure(new String[]{});
    }

    /**
     * Configures catalogs based on the args provided:
     *
     * - no args:  configures all catalogs
     * - catalog-name:  configures named catalog
     * - catalog-name credentials: configures named catalog with credentials provided
     *
     * @param args
     * @throws IncorrectConfigException
     * @throws IOException
     */
    public void configure(String[] args) throws IncorrectConfigException, IOException {
        logger.info("Executing {} with arguments: '{}'", getClass().getSimpleName(), String.join(" ", args));
        if (args.length == 0) {
            configureAll(credentials);
        } else if (args.length == 1) {
            String catalogName = args[0];
            configure(credentials, polarisClient.getCatalog(catalogName));
        } else if (args.length == 2) {
            String catalogName = args[0];
            String credentials = args[1];
            logger.debug("Credentials: " + credentials);
            addPolarisCredentialsTo(credentials, properties);
            configure(credentials, polarisClient.getCatalog(catalogName));
        } else {
            String message = String.format("Invalid arguments: %s", String.join(" ", args));
            logger.warn(message);
            logger.info(String.format("""
                     usage: [catalog-name] [credentials]
                     no args:  configures all
                     <catalog-name>:  configures named catalog
                     <catalog-name> <credentials>: configures named catalog with credentials provided                    
                     """));
            throw new IncorrectConfigException(message);
        }
    }

    private void configureAll(String credentials) throws IncorrectConfigException, IOException {
        List<String> catalogNames = polarisClient.getCatalogNames();
        logger.info("Configuring all catalogs: {}", String.join(", ", catalogNames));
        checkFailures(catalogNames, polarisClient.configureAllCatalogs(credentials));
    }

    private void configure(String credentials, PolarisCatalog... catalogs) throws IncorrectConfigException, IOException {
        List<String> catalogNames = catalogNames(catalogs);
        logger.info("Configuring catalogs: {}", String.join(", ", catalogNames));
        checkFailures(catalogNames, polarisClient.configureCatalogs(credentials, catalogs));
    }

    private List<String> catalogNames(PolarisCatalog... catalogs) {
        return Arrays.stream(catalogs).map(PolarisCatalog::catalogName).toList();
    }

    private void checkFailures(List<String> catalogNames, List<HttpResponse> responses) {
        List<String> failures = failuresOf(responses);
        if (!failures.isEmpty()) {
            String message = String.format("Failed to configure catalogs %s with failures %s", catalogNames, failures);
            logger.warn(message);
            throw new RuntimeException(message);
        }
    }

}

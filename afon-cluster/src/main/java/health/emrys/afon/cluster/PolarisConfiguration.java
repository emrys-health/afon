package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.config.PolarisCatalogClient;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static health.emrys.afon.polaris.config.PolarisCatalogClient.addPolarisCredentialsTo;
import static health.emrys.afon.polaris.config.PolarisRestClient.failuresOf;

public abstract class PolarisConfiguration {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisConfiguration.class);

    private final AfonPropertyProvider properties;
    private final PolarisCatalogClient polarisClient;

    protected PolarisConfiguration(AfonPropertyProvider properties) {
        this.properties = properties;
        this.polarisClient = new PolarisCatalogClient(properties);
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
            configureAll(getCredentials());
        } else if (args.length == 1) {
            String catalogName = args[0];
            configure(getCredentials(), polarisClient.getCatalog(catalogName));
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

    public static @NonNull String polarisCredentials(AfonPropertyProvider properties) {
        return properties.getValue(POLARIS_ID) + ":" + properties.getValue(POLARIS_SECRET);
    }

    abstract protected String getCredentials();

}

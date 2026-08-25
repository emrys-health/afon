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

    protected final AfonPropertyProvider properties;
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
     * - no args:  configures all
     * - catalog-name:  configures the single catalog
     * - catalog-name credentials: configures the single catalog with the credentials provided
     *
     * @param args
     * @throws IncorrectConfigException
     * @throws IOException
     */
    public void configure(String[] args) throws IncorrectConfigException, IOException {
        logger.info("Configuring {} with arguments: {}", getClass().getSimpleName(), String.join(" ", args));
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
        }
    }

    private void configureAll(String credentials) throws IncorrectConfigException, IOException {
        checkFailures(polarisClient.getCatalogNames(), polarisClient.configureAllCatalogs(credentials));
    }

    private void configure(String credentials, PolarisCatalog... catalogs) throws IncorrectConfigException, IOException {
        checkFailures(catalogNames(catalogs), polarisClient.configureCatalogs(credentials, catalogs));
    }

    public List<String> catalogNames(PolarisCatalog... catalogs) {
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

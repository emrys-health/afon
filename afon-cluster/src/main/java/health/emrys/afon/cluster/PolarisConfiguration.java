package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.config.PolarisCatalogClient;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static health.emrys.afon.config.AfonProperty.POLARIS_ID;
import static health.emrys.afon.config.AfonProperty.POLARIS_SECRET;
import static health.emrys.afon.polaris.config.PolarisCatalogClient.addPolarisCredentialsTo;

public abstract class PolarisConfiguration {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisConfiguration.class);

    private static final Map<AfonCloud, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new,
            AfonCloud.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

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
            configure(getCredentials(), polarisCatalog(catalogName));
        } else if (args.length == 2) {
            String catalogName = args[0];
            String credentials = args[1];
            logger.debug("Credentials: " + credentials);
            addPolarisCredentialsTo(credentials, properties);
            configure(credentials, polarisCatalog(catalogName));
        }
    }

    private PolarisCatalog polarisCatalog(String name) {
        return CLOUD_TO_CATALOG.get(AfonCloud.valueOf(name)).apply(properties);
    }

    private void configureAll(String credentials) throws IncorrectConfigException, IOException {
        checkResponses(polarisClient.configureAllCatalogs(credentials));
    }

    private void configure(String credentials, PolarisCatalog... catalogs) throws IncorrectConfigException, IOException {
        checkResponses(polarisClient.configureCatalogs(credentials, catalogs));
    }

    private void checkResponses(List<HttpResponse> responses) {
        logger.debug("HTTP responses: {}", responses);
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> badResponses = responses.stream().filter(x -> x.getCode() / 100 != 2).toList();
            if (!badResponses.isEmpty()) {
                logger.warn("Bad HTTP responses: {}", badResponses);
                throw new RuntimeException(badResponses.toString());
            }
        }
    }

    public static @NonNull String polarisCredentials(AfonPropertyProvider properties) {
        return properties.getValue(POLARIS_ID) + ":" + properties.getValue(POLARIS_SECRET);
    }

    abstract protected String getCredentials();

}

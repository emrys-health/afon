package health.emrys.afon.cluster;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import health.emrys.afon.polaris.config.PolarisCatalogClient;
import org.apache.hc.core5.http.HttpResponse;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static health.emrys.afon.polaris.config.PolarisCatalogClient.addPolarisCredentialsTo;

public abstract class AbstractPolarisConfiguration {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(AbstractPolarisConfiguration.class);

    private static final Map<AfonCloud, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new,
            AfonCloud.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

    protected final AfonPropertyProvider properties;
    private final PolarisCatalogClient polarisClient;

    protected AbstractPolarisConfiguration(AfonPropertyProvider properties) {
        this.properties = properties;
        this.polarisClient = new PolarisCatalogClient(properties);
    }

    public void configure(String[] args)
    throws IncorrectConfigException, IOException {
        logger.info("Calling {} with arguments {}", getClass().getSimpleName(), String.join(" ", args));
        if (args.length == 0) {
            initializePolaris();
        } else if (args.length == 1) {
            AfonCloud cloud = AfonCloud.valueOf(args[0]);
            initialize(cloud, this.properties);
        } else if (args.length == 2) {
            AfonCloud cloud    = AfonCloud.valueOf(args[0]);
            String credential = args[1];
            logger.info("Credential: " + credential);
            addPolarisCredentialsTo(credential, properties);
            initialize(cloud, properties);
        }
    }

    public List<HttpResponse> checkResponses(List<HttpResponse> responses) {
        logger.info("HTTP responses = {}", responses);
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> badResponses = responses.stream().filter(x -> x.getCode() / 100 != 2).toList();
            if (!badResponses.isEmpty()) {
                throw new RuntimeException(badResponses.toString());
            }
        }
        return responses;
    }

    public List<HttpResponse> initialize(AfonCloud cloud, AfonPropertyProvider properties) throws IncorrectConfigException, IOException {
        PolarisCatalog cloudConfig = CLOUD_TO_CATALOG.get(cloud).apply(properties);
        return initializePolaris(new PolarisCatalog[]{cloudConfig});
    }

    public List<HttpResponse> initializePolaris() throws IncorrectConfigException, IOException {
        return checkResponses(polarisClient.configureAllCatalogs(getCredentials()));
    }

    public List<HttpResponse> initializePolaris(PolarisCatalog[] configs) throws IncorrectConfigException, IOException {
        return checkResponses(polarisClient.configureCatalogs(getCredentials(), configs));
    }

    abstract protected String getCredentials();

}

package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static health.emrys.afon.config.AfonProperty.POLARIS_ENDPOINT;
import static health.emrys.afon.polaris.config.PolarisRestClient.failuresOf;
import static health.emrys.afon.security.SecurityUtils.protocolFor;

public class PolarisCatalogClient {

    static final  Logger     logger = org.slf4j.LoggerFactory.getLogger(PolarisCatalogClient.class);
    private final AfonPropertyProvider properties;
    private final PolarisCatalog[] catalogs;

    private static final Map<AfonCloud, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new,
            AfonCloud.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

    public PolarisCatalogClient(AfonPropertyProvider properties) {
        this.properties = properties;
        this.catalogs = new PolarisCatalog[]{
                new AwsPolarisCatalog(properties),
                new GcpPolarisCatalog(properties),
                new AzurePolarisCatalog(properties),
                new GlueAwsPolarisCatalog(properties),
                new AzureExternalPolarisCatalog(properties),
                new AwsForbiddenPolarisCatalog(properties)
        };
    }

    public List<HttpResponse> configureAllCatalogs() throws IncorrectConfigException, IOException {
        return configureCatalogs(catalogs);
    }

    public List<HttpResponse> configureCatalogs(String... catalogNames) throws IOException, IncorrectConfigException {
        return configureCatalogs(catalogsOf(catalogNames));
    }

    public List<HttpResponse> configureCatalogs(PolarisCatalog[] catalogs) throws IOException, IncorrectConfigException {
        List<String> catalogNames = catalogNames(catalogs);
        logger.info("Configuring catalogs: {}", String.join(", ", catalogNames));
        List<HttpResponse> allResponses = new ArrayList<HttpResponse>();
        PolarisConfigurer  configurer   = polarisConfigurer();
        for (PolarisCatalog catalog : catalogs) {
            logger.info("Configuring catalog: {}", catalog.catalogName());
            allResponses.addAll(configurer.configureCatalog(catalog));
        }
        checkFailures(catalogNames, allResponses);
        return allResponses;
    }

    private PolarisCatalog[] catalogsOf(String[] names) {
        return Arrays.stream(names).map(name -> catalogOf(name)).toArray(PolarisCatalog[]::new);
    }

    private PolarisCatalog catalogOf(String name) {
        return CLOUD_TO_CATALOG.get(AfonCloud.valueOf(name.toUpperCase())).apply(properties);
    }

    private List<String> catalogNames(PolarisCatalog... catalogs) {
        return Arrays.stream(catalogs).map(PolarisCatalog::catalogName).toList();
    }

    private @NonNull PolarisConfigurer polarisConfigurer() {
        String endpoint = properties.getValue(POLARIS_ENDPOINT);
        var    client   = PolarisRestClientFactory.clientFactory().createClient(properties, protocolFor(endpoint));
        return new PolarisConfigurer(client);
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
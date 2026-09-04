package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCloud;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static health.emrys.afon.polaris.config.PolarisRestClient.failuresOf;

public class PolarisCatalogManager {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisCatalogManager.class);

    private static final Map<AfonCloud, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new,
            AfonCloud.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

    private static PolarisCatalog[] catalogs(AfonPropertyProvider properties) {
        return new PolarisCatalog[]{
                new AwsPolarisCatalog(properties),
                new GcpPolarisCatalog(properties),
                new AzurePolarisCatalog(properties),
                new GlueAwsPolarisCatalog(properties),
                new AzureExternalPolarisCatalog(properties),
                new AwsForbiddenPolarisCatalog(properties)
        };
    }

    private final AfonPropertyProvider properties;
    private final PolarisCatalog[] catalogs;
    private final CatalogConfigurer configurer;

    public PolarisCatalogManager(AfonPropertyProvider properties) {
        this.properties = properties;
        this.configurer = new CatalogConfigurer(PolarisRestClientFactory.clientFactory().createClient(properties));
        this.catalogs = catalogs(properties);
    }

    public List<HttpResponse> configureAllCatalogs() throws IncorrectConfigException, IOException {
        return configureCatalogs(catalogs);
    }

    public List<HttpResponse> configureCatalogs(String... catalogNames) throws IOException, IncorrectConfigException {
        return configureCatalogs(catalogsOf(catalogNames));
    }

    public List<HttpResponse> configureCatalogs(PolarisCatalog... catalogs) throws IOException, IncorrectConfigException {
        List<String> catalogNames = catalogNames(catalogs);
        logger.info("Configuring catalogs: {}", String.join(", ", catalogNames));
        List<HttpResponse> allResponses = new ArrayList<HttpResponse>();
        for (PolarisCatalog catalog : catalogs) {
            allResponses.addAll(configurer.configureCatalog(catalog));
        }
        checkFailures(catalogNames, allResponses);
        return allResponses;
    }

    private PolarisCatalog[] catalogsOf(String... names) {
        return Arrays.stream(names).map(name -> catalogOf(name)).toArray(PolarisCatalog[]::new);
    }

    private PolarisCatalog catalogOf(String name) {
        return CLOUD_TO_CATALOG.get(AfonCloud.valueOf(name.toUpperCase())).apply(properties);
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

    public void deleteCatalog(String catalogName) throws IOException, ParseException {
        purgeNamespacesAndTables(catalogName);
        configurer.deleteCatalog(catalogName);
        purgeRoles(catalogName);
    }

    private void purgeRoles(String catalogName) throws IOException, ParseException {
        for (String roleName : configurer.catalogRoleNames(catalogName)) {
            configurer.deleteCatalogRole(catalogName, roleName);
        }
    }

    private void purgeNamespacesAndTables(String catalogName) throws IOException, ParseException {
        for (String namespace : configurer.namespacesOf(catalogName)) {
            deleteAllTables(catalogName, namespace);
            deleteNamespace(catalogName, namespace);
        }
    }

    public void deleteNamespace(String catalogName, String namespace) throws IOException {
        configurer.deleteNamespace(catalogName, namespace);
    }

    public List<HttpResponse> deleteAllTables(String catalogName, String namespace) throws IOException, ParseException {
        List<HttpResponse> responses = new ArrayList<>();
        for (String table : configurer.tableNamesOf(catalogName, namespace)) {
            responses.add(deleteTable(catalogName, namespace, table));
        }
        return responses;
    }

    public HttpResponse deleteTable(String catalogName, String namespace, String table) throws IOException {
        return configurer.deleteTable(catalogName, namespace, table);
    }
}
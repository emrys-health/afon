package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.ConfigUtils;
import health.emrys.afon.polaris.catalogs.*;
import org.apache.hc.core5.http.ClassicHttpResponse;
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

    private static final Map<AfonCatalog, Function<AfonPropertyProvider, PolarisCatalog>>
            CLOUD_TO_CATALOG = Map.of(
            AfonCatalog.AWS, AwsPolarisCatalog::new,
            AfonCatalog.AZURE, AzurePolarisCatalog::new,
            AfonCatalog.AZURE_EXTERNAL, AzureExternalPolarisCatalog::new,
            AfonCatalog.GCP, GcpPolarisCatalog::new,
            AfonCatalog.GCP_EXTERNAL, GcpExternalPolarisCatalog::new);

    private static PolarisCatalog[] catalogs(AfonPropertyProvider properties) {
        List<PolarisCatalog> catalogs = new ArrayList<>();
        switch ( ConfigUtils.afonCloud() ){
            case "aws":
                catalogs.add(new AwsPolarisCatalog(properties));
                catalogs.add(new AwsForbiddenPolarisCatalog(properties));
                catalogs.add(new GlueAwsPolarisCatalog(properties));
                break;
            case "azure":
                catalogs.add(new AzurePolarisCatalog(properties));
                catalogs.add(new AzureExternalPolarisCatalog(properties));
                break;
            case "gcp":
                catalogs.add(new GcpPolarisCatalog(properties));
                break;
            default:
                catalogs.add(new AwsPolarisCatalog(properties));
                catalogs.add(new AwsForbiddenPolarisCatalog(properties));
                catalogs.add(new GlueAwsPolarisCatalog(properties));
                catalogs.add(new AzurePolarisCatalog(properties));
                catalogs.add(new AzureExternalPolarisCatalog(properties));
                catalogs.add(new GcpPolarisCatalog(properties));
        }
        return catalogs.toArray(PolarisCatalog[]::new);
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
        return CLOUD_TO_CATALOG.get(AfonCatalog.valueOf(name.toUpperCase())).apply(properties);
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

    public HttpResponse grantCatalogAdmin(PolarisCatalog catalog, String warehouseName) throws IOException, IncorrectConfigException {
        return configurer.adminGrantsCatalog(catalog, warehouseName);
    }

    /**
     * Creates the catalog without first deleting any existing one, so an already-existing catalog
     * (and anything set up against it - grants, roles, cached readers) is left untouched.
     */
    public ClassicHttpResponse createCatalogIfAbsent(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        return configurer.createCatalogIfAbsent(catalog);
    }

    public ClassicHttpResponse getCatalogConfig(String catalogName) throws IOException {
        return configurer.getCatalogConfig(catalogName);
    }

    public ClassicHttpResponse getCatalog(String catalogName) throws IOException {
        return configurer.getCatalog(catalogName);
    }

    public ClassicHttpResponse getNamespace(String catalogName, String namespace) throws IOException {
        return configurer.getNamespace(catalogName, namespace);
    }

    public ClassicHttpResponse getTable(String catalogName, String namespace, String table) throws IOException {
        return configurer.getTable(catalogName, namespace, table);
    }
}
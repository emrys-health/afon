package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonPropertyProvider;
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

    /**
     * Factories keyed by {@link AfonCatalog#toString()}'s canonical name, so the catalogs actually
     * configured always match whatever {@link AfonCatalogs} resolves to - the built-in default set,
     * or a user-supplied {@code CATALOG_NAMES_CSV} - rather than a separately maintained list.
     */
    private static final Map<String, Function<AfonPropertyProvider, PolarisCatalog>> CATALOG_FACTORIES = Map.ofEntries(
            Map.entry("aws", AwsPolarisCatalog::new),
            Map.entry("aws_forbidden", AwsForbiddenPolarisCatalog::new),
            Map.entry("aws_trino", AwsTrinoPolarisCatalog::new),
            Map.entry("aws_external", AwsExternalPolarisCatalog::new),
            Map.entry("azure", AzurePolarisCatalog::new),
            Map.entry("azure_external", AzureExternalPolarisCatalog::new),
            Map.entry("azure_forbidden", AzureForbiddenPolarisCatalog::new),
            Map.entry("gcp", GcpPolarisCatalog::new),
            Map.entry("gcp_external", GcpExternalPolarisCatalog::new),
            Map.entry("gcp_forbidden", GcpForbiddenPolarisCatalog::new));

    private final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;
    private final CatalogConfigurer configurer;

    public PolarisCatalogManager(AfonPropertyProvider properties) {
        this.properties = properties;
        this.afonCatalogs = new AfonCatalogs(properties);
        this.configurer = new CatalogConfigurer(PolarisRestClientFactory.clientFactory().createClient(properties));
    }

    /**
     * Built lazily (only when actually configuring every catalog) since resolving every
     * {@link AfonCatalog} requires properties for every cloud - eagerly building this in the
     * constructor would break any cloud-scoped run (eg {@code afon.cloud=aws}) that only loads
     * that cloud's properties.
     */
    private PolarisCatalog[] catalogs() {
        return afonCatalogs.all().stream().map(this::catalogOf).toArray(PolarisCatalog[]::new);
    }

    public List<HttpResponse> configureAllCatalogs() throws IncorrectConfigException, IOException {
        return configureCatalogs(catalogs());
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
        return Arrays.stream(names).map(afonCatalogs::get).map(this::catalogOf).toArray(PolarisCatalog[]::new);
    }

    private PolarisCatalog catalogOf(AfonCatalog catalog) {
        Function<AfonPropertyProvider, PolarisCatalog> factory = CATALOG_FACTORIES.get(catalog.toString());
        if (factory == null) {
            throw new IllegalArgumentException("No PolarisCatalog implementation registered for catalog '" + catalog + "'");
        }
        return factory.apply(properties);
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
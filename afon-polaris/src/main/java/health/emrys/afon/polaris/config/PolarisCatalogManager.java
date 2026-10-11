package health.emrys.afon.polaris.config;

import health.emrys.afon.config.AfonCatalog;
import health.emrys.afon.config.AfonCatalogs;
import health.emrys.afon.config.AfonCloud;
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

import static health.emrys.afon.rest.HttpResponses.failuresOf;

public class PolarisCatalogManager {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PolarisCatalogManager.class);

    private static final String CATALOG_ADMIN_ROLE = "catalog_admin";
    private static final String EXTERNAL_CATALOG_TYPE = "external";

    @FunctionalInterface
    private interface CatalogFactory {
        PolarisCatalog create(AfonCatalog catalog, AfonPropertyProvider properties);
    }

    /**
     * The two generic catalog "types": every {@code internal}/{@code external} catalog is built
     * purely from its {@link AfonCatalog} definition (see {@link AfonCatalogs}), so adding one
     * needs no code change - only configuration (a catalog id in {@code <CLOUD>_CATALOG_IDS}
     * plus its {@code <CLOUD>_CATALOG_TYPE_<id>}/{@code <CLOUD>_BUCKET_NAME_<id>}/
     * {@code <CLOUD>_ADMIN_GRANTS_<id>}, conventionally in {@code catalogs-<cloud>.properties}).
     */
    private static final Map<AfonCloud, CatalogFactory> INTERNAL_CATALOG_FACTORIES = Map.of(
            AfonCloud.AWS, AwsPolarisCatalog::new,
            AfonCloud.AZURE, AzurePolarisCatalog::new,
            AfonCloud.GCP, GcpPolarisCatalog::new);

    private static final Map<AfonCloud, CatalogFactory> EXTERNAL_CATALOG_FACTORIES = Map.of(
            AfonCloud.AWS, AwsExternalPolarisCatalog::new,
            AfonCloud.AZURE, AzureExternalPolarisCatalog::new,
            AfonCloud.GCP, GcpExternalPolarisCatalog::new);

    /**
     * Any other catalog type (eg the Glue-backed one) isn't reducible to the generic
     * "internal"/"external" shapes, so keeps a hand-written {@link PolarisCatalog} subclass
     * registered explicitly here, keyed by its {@link AfonCatalog#type()}. Built purely from its
     * {@link AfonCatalog} definition too, same as the generic shapes above.
     */
    private static final Map<String, CatalogFactory> CUSTOM_CATALOG_FACTORIES = Map.of(
            "glue", GlueAwsPolarisCatalog::new);

    private final AfonPropertyProvider properties;
    private final AfonCatalogs afonCatalogs;
    private final CatalogConfigurer configurer;

    public PolarisCatalogManager(AfonPropertyProvider properties) {
        this(properties, new AfonCatalogs(properties),
             new CatalogConfigurer(PolarisRestClientFactory.clientFactory().createClient(properties)));
    }

    PolarisCatalogManager(AfonPropertyProvider properties, AfonCatalogs afonCatalogs, CatalogConfigurer configurer) {
        this.properties = properties;
        this.afonCatalogs = afonCatalogs;
        this.configurer = configurer;
    }

    /**
     * Built lazily (only when actually configuring every catalog) since resolving every
     * {@link AfonCatalog} requires properties for every cloud - eagerly building this in the
     * constructor would break any cloud-scoped run (eg {@code afon.cloud=aws}) that only loads
     * that cloud's properties. Excludes glue, which was never part of "all catalogs" - it can still
     * be configured explicitly by name.
     */
    private PolarisCatalog[] catalogs() {
        return afonCatalogs.allExcluding("glue").stream().map(this::catalogOf).toArray(PolarisCatalog[]::new);
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
        return switch (catalog.type()) {
            case "internal" -> INTERNAL_CATALOG_FACTORIES.get(catalog.cloud()).create(catalog, properties);
            case "external" -> EXTERNAL_CATALOG_FACTORIES.get(catalog.cloud()).create(catalog, properties);
            default -> customCatalogOf(catalog);
        };
    }

    private PolarisCatalog customCatalogOf(AfonCatalog catalog) {
        CatalogFactory factory = CUSTOM_CATALOG_FACTORIES.get(catalog.type());
        if (factory == null) {
            throw new IllegalArgumentException("No PolarisCatalog factory registered for catalog type '"
                    + catalog.type() + "' (catalog '" + catalog.id() + "')");
        }
        return factory.create(catalog, properties);
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

    /**
     * Deletes the catalog with the given id, with everything in it.  An {@code external} catalog is only a
     * federation to a remote catalog, whose namespaces and tables are not ours to purge (and which Polaris
     * cannot list once the remote catalog is gone), so only its own roles are purged.
     *
     * @param catalogId the id of the catalog, as for creating it (see {@link AfonCatalogs#get})
     */
    public void deleteCatalog(String catalogId) throws IOException, ParseException {
        AfonCatalog catalog = afonCatalogs.get(catalogId);
        String catalogName = catalog.name();
        if (EXTERNAL_CATALOG_TYPE.equals(catalog.type())) {
            logger.info("Catalog {} is federated, leaving its remote namespaces and tables", catalogName);
        } else {
            purgeNamespacesAndTables(catalogName);
        }
        purgeRoles(catalogName);
        configurer.deleteCatalog(catalogName);
    }

    /**
     * Deletes the roles of the catalog, other than its built-in {@code catalog_admin}, which goes with the catalog.
     */
    private void purgeRoles(String catalogName) throws IOException, ParseException {
        for (String roleName : configurer.catalogRoleNames(catalogName)) {
            if (!CATALOG_ADMIN_ROLE.equals(roleName)) {
                configurer.deleteCatalogRole(catalogName, roleName);
            }
        }
    }

    private void purgeNamespacesAndTables(String catalogName) throws IOException, ParseException {
        for (String namespace : configurer.namespacesOf(catalogName)) {
            for (String table : configurer.tableNamesOf(catalogName, namespace)) {
                configurer.deleteTable(catalogName, namespace, table);
            }
            configurer.deleteNamespace(catalogName, namespace);
        }
    }

    public void deleteNamespace(String catalogId, String namespace) throws IOException {
        configurer.deleteNamespace(afonCatalogs.get(catalogId).name(), namespace);
    }

    public List<HttpResponse> deleteAllTables(String catalogId, String namespace) throws IOException, ParseException {
        String catalogName = afonCatalogs.get(catalogId).name();
        List<HttpResponse> responses = new ArrayList<>();
        for (String table : configurer.tableNamesOf(catalogName, namespace)) {
            responses.add(configurer.deleteTable(catalogName, namespace, table));
        }
        return responses;
    }

    public HttpResponse deleteTable(String catalogId, String namespace, String table) throws IOException {
        return configurer.deleteTable(afonCatalogs.get(catalogId).name(), namespace, table);
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
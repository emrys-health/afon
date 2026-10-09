package health.emrys.afon.polaris.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisSerialiser;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.polaris.core.admin.model.CatalogPrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static health.emrys.afon.rest.HttpResponses.isFailure;
import static java.lang.String.format;

public class CatalogConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(CatalogConfigurer.class);

    public static String catalogsURI() {
        return "/api/management/v1/catalogs";
    }

    public static String catalogsURI(String catalogName) {
        return format("%s/%s", catalogsURI(), catalogName);
    }

    public static String catalogConfigURI(String catalogName) {
        return format("/api/catalog/v1/config?warehouse=%s", catalogName);
    }

    public static String catalogRolesURI(String catalogName) {
        return format("/api/management/v1/catalogs/%s/catalog-roles", catalogName);
    }

    public static String catalogRolesURI(String roleName, String catalogName) {
        return format("/api/management/v1/principal-roles/%s/catalog-roles/%s", roleName, catalogName);
    }

    public static String catalogRolesGrantURI(String catalogName, String catalogRole) {
        return format("/api/management/v1/catalogs/%s/catalog-roles/%s/grants", catalogName, catalogRole);
    }

    public static String catalogRolesAdminGrantsURI(String catalogName) {
        return format("%s/%s/catalog-roles/catalog_admin/grants", catalogsURI(), catalogName);
    }

    public static String namespacesURI(String catalogName) {
        return format("/api/catalog/v1/%s/namespaces/", catalogName);
    }

    public static String namespaceURI(String catalogName, String namespace) {
        return format("%s/%s", namespacesURI(catalogName), namespace);
    }

    public static String tablesURI(String catalogName, String namespace) {
        return tableURI(catalogName, namespace, "");
    }

    public static String tableURI(String catalogName, String namespace, String tableName) {
        return format("/api/catalog/v1/%s/namespaces/%s/tables/%s", catalogName, namespace, tableName);
    }

    public static String principalsURI() {
        return "/api/management/v1/principals";
    }

    public static String principalRolesURI() {
        return "/api/management/v1/principal-roles";
    }

    public static String principalRolesURI(String principal) {
        return format("/api/management/v1/principals/%s/principal-roles", principal);
    }

    private final PolarisRestClient client;
    private final PolarisSerialiser serialiser;
    private final String accessToken;

    public CatalogConfigurer(PolarisRestClient client) {
        this.client = client;
        this.serialiser = new PolarisSerialiser();
        this.accessToken = client.getAccessToken();
    }

    /**
     * Creates a catalog and, depending on {@link PolarisCatalog#adminGrants()},
     * additionally invokes {@link #adminGrantsCatalog(PolarisCatalog)}.
     *
     * @param catalog the {@link PolarisCatalog}
     * @return A List of {@link HttpResponse}
     * @throws IOException
     * @throws IncorrectConfigException
     */
    public List<HttpResponse> configureCatalog(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        logger.info("Configuring catalog {}", catalog.catalogName());
        List<HttpResponse> responses = new ArrayList<>();
        responses.add(createCatalog(catalog));
        if (catalog.adminGrants()) {
            responses.add(adminGrantsCatalog(catalog));
        }
        return responses;
    }

    private ClassicHttpResponse adminGrantsCatalog(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        return adminGrantsCatalog(catalog, catalog.catalogName());
    }

    public ClassicHttpResponse adminGrantsCatalog(PolarisCatalog catalog, String catalogName) throws IOException, IncorrectConfigException {
        return client.put(catalogRolesAdminGrantsURI(catalogName), catalog.catalogAdminGrants(), accessToken);
    }

    // TODO: check if catalog exist before attempting to delete it, failure is expected if there is no catalog
    public ClassicHttpResponse createCatalog(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        deleteCatalog(catalog);
        return createCatalogIfAbsent(catalog);
    }

    /**
     * Creates the catalog without first deleting any existing one, so an already-existing catalog
     * (and anything set up against it - grants, roles, cached readers) is left untouched.
     */
    public ClassicHttpResponse createCatalogIfAbsent(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        String catalogName = catalog.catalogName();
        logger.info("Creating catalog {}", catalogName);
        ClassicHttpResponse response = client.post(catalogsURI(), catalog.catalogConfig(), accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to create catalog {}", catalogName);
        }
        return response;
    }

    private ClassicHttpResponse deleteCatalog(PolarisCatalog catalog) throws IOException {
        return deleteCatalog(catalog.catalogName());
    }

    public ClassicHttpResponse deleteCatalog(String catalogName) throws IOException {
        logger.info("Deleting catalog {}", catalogName);
        ClassicHttpResponse response = client.delete(catalogsURI(catalogName), accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to delete catalog {}", catalogName);
        }
        return response;
    }

    public List<String> catalogRoleNames(String catalogName) throws IOException, ParseException {
        List<String> roleNames = new ArrayList<>();
        for (JsonNode node : getNodes(catalogRolesURI(catalogName), "roles")) {
            roleNames.add(node.get("name").toString().replace("\"", ""));
        }
        return roleNames;
    }

    public List<String> namespacesOf(String catalogName) throws IOException, ParseException {
        List<String> namespaceNames = new ArrayList<>();
        for (JsonNode namespaces : getNodes(namespacesURI(catalogName), "namespaces")) {
            if (namespaces.isArray()) {
                for (JsonNode node : namespaces) {
                    namespaceNames.add(node.textValue());
                }
            }
        }
        return namespaceNames;
    }

    public ClassicHttpResponse deleteNamespace(String catalogName, String namespace) throws IOException {
        logger.info("Deleting namespace {} from catalog {}", namespace, catalogName);
        ClassicHttpResponse response = client.delete(namespaceURI(catalogName, namespace), accessToken);
        if (isFailure(response)) {
            logger.warn("Could not delete namespace {} from {}", namespace, catalogName);
        }
        return response;
    }

    public List<String> tableNamesOf(String catalogName, String namespace) throws IOException, ParseException {
        List<String> tableNames = new ArrayList<>();
        for (JsonNode node : getNodes(tablesURI(catalogName, namespace), "identifiers")) {
            tableNames.add(node.get("name").toString().replace("\"", ""));
        }
        return tableNames;
    }

    public ClassicHttpResponse deleteTable(String catalogName, String namespace, String table) throws IOException {
        logger.info("Deleting table {} from {}.{}", table, catalogName, namespace);
        ClassicHttpResponse response = client.delete(tableURI(catalogName, namespace, table), accessToken);
        if (isFailure(response)) {
            logger.warn("Could not delete table {} in {}", table, catalogName);
        }
        return response;
    }

    public ClassicHttpResponse getCatalogConfig(String catalogName) throws IOException {
        return client.get(catalogConfigURI(catalogName), accessToken);
    }

    public ClassicHttpResponse getCatalog(String catalogName) throws IOException {
        return client.get(catalogsURI(catalogName), accessToken);
    }

    public ClassicHttpResponse getNamespace(String catalogName, String namespace) throws IOException {
        return client.get(namespaceURI(catalogName, namespace), accessToken);
    }

    public ClassicHttpResponse getTable(String catalogName, String namespace, String table) throws IOException {
        return client.get(tableURI(catalogName, namespace, table) + "?snapshots=all", accessToken);
    }

    private Iterable<JsonNode> getNodes(String url, String rootNode) throws IOException, ParseException {
        logger.info("Retrieving nodes from url {} with root node {}", url, rootNode);
        ClassicHttpResponse response = client.get(url, accessToken);
        JsonNode root = new ObjectMapper().readTree(EntityUtils.toString(response.getEntity()));
        JsonNode identifiers = root.get(rootNode);
        if (identifiers == null || !identifiers.isArray()) {
            logger.warn("No nodes found from url {} with root node of {}", url, rootNode);
            return Collections.emptyList();
        }
        return identifiers;
    }

    public ClassicHttpResponse associatePrincipalRole(String catalogName, String principalRoleName, String catalogRoleName) throws IOException, IncorrectConfigException {
        logger.info("Associating principal role {} with catalog role {} in catalog {}", principalRoleName, catalogRoleName, catalogName);
        String uri = catalogRolesURI(principalRoleName, catalogName);
        String json = serialiser.catalogRoleCreation(catalogRoleName);
        ClassicHttpResponse response = client.put(uri, json, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to associate principal role {} with catalog role {} in catalog {}", principalRoleName, catalogRoleName, catalogName);
        }
        return response;
    }

    public ClassicHttpResponse createCatalogRoleGrant(String catalogName, String catalogRoleName, CatalogPrivilege privilege) throws IncorrectConfigException, IOException {
        String uri = catalogRolesGrantURI(catalogName, catalogRoleName);
        String json = serialiser.catalogRoleGrant(privilege);
        ClassicHttpResponse response = client.put(uri, json, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to create catalog role grant {}", privilege);
        }
        return response;
    }

    public ClassicHttpResponse createCatalogRole(String catalogName, String roleName) throws IOException, IncorrectConfigException {
        logger.info("Creating role {} for catalog {}", roleName, catalogName);
        String uri = catalogRolesURI(catalogName);
        String json = serialiser.catalogRoleCreation(roleName);
        ClassicHttpResponse response = client.post(uri, json, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to create catalog role {}", roleName);
        }
        return response;
    }

    /**
     * Deletes the catalog role if the catalog has it.  It asks first, rather than deleting a role that may be absent and
     * ignoring the 404, as Polaris 1.6.0 answers 204 to the deletion of a role that a federated (external) catalog does not
     * have, and drops the whole catalog instead, so that every request that follows cannot find it.
     */
    public void deleteCatalogRole(String catalogName, String roleName) throws IOException {
        if (!hasCatalogRole(catalogName, roleName)) {
            logger.info("No role {} for catalog {} to delete", roleName, catalogName);
            return;
        }
        logger.info("Deleting role {} for catalog {}", roleName, catalogName);
        String uri = catalogRolesURI(catalogName) + "/" + roleName;
        ClassicHttpResponse response = client.delete(uri, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to delete role {}", roleName);
        }
    }

    private boolean hasCatalogRole(String catalogName, String roleName) throws IOException {
        try {
            return catalogRoleNames(catalogName).contains(roleName);
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    /**
     * Deletes the principal role if it is present, returning a 404 harmlessly when it is not. Polaris
     * cascades this: removing a principal role also drops its associations with catalog roles, so this
     * is enough to clear a stale role left by a previous run before it is recreated.
     */
    public ClassicHttpResponse deletePrincipalRole(String principalRoleName) throws IOException {
        logger.info("Deleting principal role {}", principalRoleName);
        String uri = principalRolesURI() + "/" + principalRoleName;
        ClassicHttpResponse response = client.delete(uri, accessToken);
        if (isFailure(response) && response.getCode() != HttpStatus.SC_NOT_FOUND) {
            logger.warn("Failed to delete principal role {}", principalRoleName);
        }
        return response;
    }

    public ClassicHttpResponse createPrincipalRole(String principalRoleName) throws IOException, IncorrectConfigException {
        logger.info("Creating principal role {}", principalRoleName);
        String uri = principalRolesURI();
        String json = serialiser.principalRoleCreation(principalRoleName);
        ClassicHttpResponse response = client.post(uri, json, accessToken);
        if (isFailure(response) && response.getCode() != HttpStatus.SC_CONFLICT) { // conflict: it already exists
            logger.warn("Failed to create principal role {}", principalRoleName);
        }
        return response;
    }

    public ClassicHttpResponse associatePrincipalRole(String principalName, String principalRoleName) throws IncorrectConfigException, IOException {
        logger.info("Associating principal {} with role {}", principalName, principalRoleName);
        String uri = principalRolesURI(principalName);
        String json = serialiser.principalRoleCreation(principalRoleName);
        ClassicHttpResponse response = client.put(uri, json, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to associate principal {} with role {}", principalName, principalRoleName);
        }
        return response;
    }

    public ClassicHttpResponse createPrincipal(String principalName) throws IncorrectConfigException, IOException {
        logger.info("Creating principal {}", principalName);
        String uri = principalsURI();
        String json = serialiser.principalCreation(principalName);
        ClassicHttpResponse response = client.post(uri, json, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to create principal {}", principalName);
        }
        return response;
    }

    public ClassicHttpResponse deletePrincipal(String principalName) throws IOException {
        logger.info("Deleting principal {}", principalName);
        String uri = principalsURI() + "/" + principalName;
        ClassicHttpResponse response = client.delete(uri, accessToken);
        if (isFailure(response)) {
            logger.warn("Failed to delete principal {}", principalName);
        }
        return response;
    }

}

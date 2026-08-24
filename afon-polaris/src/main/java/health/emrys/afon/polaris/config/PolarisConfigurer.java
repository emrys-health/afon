package health.emrys.afon.polaris.config;

import health.emrys.afon.polaris.catalogs.IncorrectConfigException;
import health.emrys.afon.polaris.catalogs.PolarisCatalog;
import health.emrys.afon.polaris.catalogs.PolarisSerialiser;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.polaris.core.admin.model.PrincipalWithCredentials;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;

public class PolarisConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(PolarisConfigurer.class);

    private final PolarisRestClient client;

    public static String catalogsURI() {
        return "/api/management/v1/catalogs";
    }

    public static String catalogsURI(String catalogName) {
        return format(
            "%s/%s",
            catalogsURI(),
            catalogName);
    }

    public static String catalogRolesURI(String catalogName) {
        return format("/api/management/v1/catalogs/%s/catalog-roles", catalogName);
    }

    public static String catalogeRolesURI(String roleName, String catalogName) {
        return format(
            "/api/management/v1/principal-roles/%s/catalog-roles/%s",
            roleName,
            catalogName);
    }

    public static String catalogRolesGrantURI(String catalogName, String catalogRole) {
        return format("/api/management/v1/catalogs/%s/catalog-roles/%s/grants", catalogName, catalogRole);
    }

    public static String catalogRolesAdminGrantsURI(String catalogName) {
        return format(
            "%s/%s/catalog-roles/catalog_admin/grants",
            catalogsURI(),
            catalogName);
    }

    public static String principalsURI() {
        return "/api/management/v1/principals";
    }

    public static String principalRolesURI() {
        return "/api/management/v1/principal-roles";
    }

    public static String principalRolesURI(String principal) {
        return format(
            "/api/management/v1/principals/%s/principal-roles",
            principal);
    }

    public static String namespacesURI(
        String catalogName
    ) {
        return format("/api/catalog/v1/%s/namespaces/", catalogName);
    }

    public static String namespaceURI(
        String catalogName,
        String namespace
    ) {
        return format("%s/%s", namespacesURI(catalogName), namespace);
    }

    public static String tablesURI(String catalogName, String namespace) {
        return tableURI(catalogName, namespace, "");
    }

    public static String tableURI(
        String catalogName,
        String namespace,
        String tableName
    ) {
        return format(
            "/api/catalog/v1/%s/namespaces/%s/tables/%s",
            catalogName, namespace, tableName);
    }

    private final String accessToken;

    public PolarisConfigurer(PolarisRestClient client) {
        this.client = client;
        this.accessToken = client.getAccessToken();
    }

    public static boolean isFailure(HttpResponse response) {
        return response == null || response.getCode() / 100 != 2;
    }

    public static @NonNull String tokensURI(String endpoint) {
        return format("%s/api/catalog/v1/oauth/tokens", endpoint);
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
        logger.info("Access token {}", accessToken);
        List<HttpResponse> responses = new ArrayList<>();
        responses.add(createCatalog(catalog));
        if ( catalog.adminGrants() ){
            responses.add(adminGrantsCatalog(catalog));
        }
        logIfProblems(responses);
        return responses;
    }

    private HttpResponse adminGrantsCatalog(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        return client.put(
                catalogRolesAdminGrantsURI(catalog.catalogName()),
                catalog.catalogAdminGrants(),
                accessToken);
    }

    // TODO: check if catalog exist before attempting to delete it
    public HttpResponse createCatalog(PolarisCatalog catalog) throws IOException, IncorrectConfigException {
        client.delete(catalogsURI(catalog.catalogName()), accessToken); // this failing is expected if there is no catalog
        return client.post(
            catalogsURI(),
            catalog.catalogConfig(),
            accessToken);
    }

    public PrincipalWithCredentials configureUser(
        String accessToken,
        String principalName
    ) throws IncorrectConfigException, IOException {
        String            uri        = principalsURI();
        PolarisSerialiser serialiser = new PolarisSerialiser();
        String            json       = serialiser.principalCreation(principalName);
        if (isFailure(deletePrincipalIfPresent(accessToken, principalName))) {
            logger.warn("Could not delete principal {}", principalName);
        }
        logger.info(format("Creating user %s", principalName));
        try (ClassicHttpResponse response = client.post(uri, json, accessToken);
             InputStream is = response.getEntity().getContent()) {
            return serialiser.fromJson(is, PrincipalWithCredentials.class);
        }
    }

    private HttpResponse deletePrincipalIfPresent(String accessToken, String principalName) throws IOException {
        logger.info("Deleting principal {}", principalName);
        String uri = principalsURI() + "/" + principalName;
        try (ClassicHttpResponse response = client.delete(uri, accessToken)) {
            logger.info("Deleting principal {} resulted in HTTP code {}", principalName, response.getCode());
            logIfProblem(response);
            return response;
        }
    }

    private static void logIfProblems(List<HttpResponse> responses) {
        responses.stream().forEach(PolarisConfigurer::logIfProblem);
    }

    private static void logIfProblem(HttpResponse response) {
        if (response.getCode() / 100 != 2) {
            logger.error("HTTP code {}. Error: {}", response.getCode(), response.getReasonPhrase());
        }
    }


}
